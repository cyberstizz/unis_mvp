package com.unis.service;

import com.unis.dto.SearchDto;
import com.unis.entity.VotingInterval;
import com.unis.repository.VotingIntervalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RaceStandingsService raceStandingsService;
    private final RaceClockService raceClock;
    private final VotingIntervalRepository votingIntervalRepository;

    /**
     * Most people the Discover Users rail orders in one place. search_all is
     * asked for up to this many; anyone past it is left off the browse rail
     * (still findable by name). Raise it if a jurisdiction outgrows it.
     */
    static final int USER_BROWSE_CAP = 2000;
    private static final long USER_BROWSE_TTL_MS = 60_000;

    private record UserBrowse(List<SearchDto.Result> ordered, long builtAt) {}
    private final Map<String, UserBrowse> userBrowseCache = new ConcurrentHashMap<>();

    @Autowired
    public SearchService(NamedParameterJdbcTemplate jdbcTemplate,
                         RaceStandingsService raceStandingsService,
                         RaceClockService raceClock,
                         VotingIntervalRepository votingIntervalRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.raceStandingsService = raceStandingsService;
        this.raceClock = raceClock;
        this.votingIntervalRepository = votingIntervalRepository;
    }

    /**
     * Lightweight autocomplete suggestions — grouped by type with a top result.
     * Target: < 150ms response time.
     */
    public SearchDto.SuggestionsResponse getSuggestions(String query, int limit) {
        if (query == null || query.trim().length() < 2) {
            return SearchDto.SuggestionsResponse.builder()
                    .artists(Collections.emptyList())
                    .songs(Collections.emptyList())
                    .jurisdictions(Collections.emptyList())
                    .totalCount(0)
                    .build();
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("query", query.trim())
                .addValue("limit", limit);

        String sql = "SELECT * FROM search_suggestions(:query, :limit)";

        List<SearchDto.Suggestion> allResults = jdbcTemplate.query(sql, params, (rs, rowNum) ->
                SearchDto.Suggestion.builder()
                        .id(UUID.fromString(rs.getString("id")))
                        .name(rs.getString("name"))
                        .subtitle(rs.getString("subtitle"))
                        .type(rs.getString("type"))
                        .artworkUrl(rs.getString("artwork_url"))
                        .score(rs.getInt("score"))
                        .similarityScore(rs.getFloat("similarity_score"))
                        .build()
        );

        // Group by type
        Map<String, List<SearchDto.Suggestion>> grouped = allResults.stream()
                .collect(Collectors.groupingBy(SearchDto.Suggestion::getType));

        // Top result = highest similarity score overall
        SearchDto.Suggestion topResult = allResults.isEmpty() ? null : allResults.get(0);

        return SearchDto.SuggestionsResponse.builder()
                .topResult(topResult)
                .artists(grouped.getOrDefault("artist", Collections.emptyList()))
                .songs(grouped.getOrDefault("song", Collections.emptyList()))
                .jurisdictions(grouped.getOrDefault("jurisdiction", Collections.emptyList()))
                .totalCount(allResults.size())
                .build();
    }

    /**
     * Full search with type filtering, jurisdiction scoping, and pagination.
     */
    public SearchDto.SearchResponse search(String query, String type, UUID jurisdictionId,
                                            int limit, int offset) {
        String normalizedQuery = (query == null ? "" : query.trim());                
        if (normalizedQuery.isEmpty() && jurisdictionId == null) {                   
            return SearchDto.SearchResponse.builder()
                    .results(Collections.emptyList())
                    .query(normalizedQuery)                                          
                    .type(type != null ? type : "all")                             
                    .count(0)
                    .offset(offset)
                    .limit(limit)
                    .build();
        }

        // Browsing the Users rail (no search text): artists first in chart
        // order, then listeners by points. Typing keeps best-match order.
        if ("user".equals(type) && normalizedQuery.isEmpty()) {
            List<SearchDto.Result> page = browseUsers(jurisdictionId, limit, offset);
            return SearchDto.SearchResponse.builder()
                    .results(page)
                    .query(query)
                    .type(type)
                    .count(page.size())
                    .offset(offset)
                    .limit(limit)
                    .build();
        }

        List<SearchDto.Result> results = searchAll(normalizedQuery, type != null ? type : "all",
                jurisdictionId, limit, offset);

        return SearchDto.SearchResponse.builder()
                .results(results)
                .query(query)
                .type(type != null ? type : "all")
                .count(results.size())
                .offset(offset)
                .limit(limit)
                .build();
    }

    /**
     * One page of the Users rail, browsing a jurisdiction:
     *   1. Artists, in the order of this week's chart for that jurisdiction —
     *      the same board as the Charts page, midnight rule included (until
     *      anyone scores in a new week, last week's final order).
     *   2. Then listeners, most points first, newest accounts last.
     *
     * Who appears is still search_all's call (deleted, hidden and suspended
     * accounts stay out exactly as before); only the order is decided here.
     * The ordered list is kept for a minute per jurisdiction and day, so
     * paging is stable and quick.
     */
    private List<SearchDto.Result> browseUsers(UUID jurisdictionId, int limit, int offset) {
        LocalDate day = raceClock.viewerDateForRace(jurisdictionId);
        String key = jurisdictionId + "|" + day;
        long now = System.currentTimeMillis();

        UserBrowse cached = userBrowseCache.get(key);
        if (cached == null || now - cached.builtAt() > USER_BROWSE_TTL_MS) {
            cached = new UserBrowse(orderUsers(jurisdictionId, day), now);
            userBrowseCache.put(key, cached);
            userBrowseCache.entrySet().removeIf(e -> now - e.getValue().builtAt() > USER_BROWSE_TTL_MS);
        }

        List<SearchDto.Result> all = cached.ordered();
        int from = Math.min(Math.max(offset, 0), all.size());
        int to = Math.min(from + Math.max(limit, 0), all.size());
        return new ArrayList<>(all.subList(from, to));
    }

    private List<SearchDto.Result> orderUsers(UUID jurisdictionId, LocalDate day) {
        List<SearchDto.Result> people = searchAll("", "user", jurisdictionId, USER_BROWSE_CAP, 0);

        // Chart position for every artist in the jurisdiction this week,
        // scored or not (the board's own tiebreaks order the rest).
        Map<UUID, Integer> chartOrder = new HashMap<>();
        VotingInterval weekly = votingIntervalRepository.findByName("Weekly").orElse(null);
        if (weekly != null) {
            List<RankingService.Candidate> board = raceStandingsService.resolve(
                    "artist", jurisdictionId, null, weekly, day, USER_BROWSE_CAP, 0).candidates();
            for (int i = 0; i < board.size(); i++) chartOrder.put(board.get(i).targetId(), i);
        }

        Comparator<SearchDto.Result> byPoints = Comparator
                .comparing((SearchDto.Result r) -> r.getScore() == null ? 0 : r.getScore(), Comparator.reverseOrder())
                .thenComparing(r -> r.getName() == null ? "" : r.getName().toLowerCase(Locale.ROOT));

        List<SearchDto.Result> artists = new ArrayList<>();
        List<SearchDto.Result> listeners = new ArrayList<>();
        for (SearchDto.Result r : people) {
            if (isArtist(r)) artists.add(r); else listeners.add(r);
        }
        artists.sort(Comparator
                .comparing((SearchDto.Result r) -> chartOrder.getOrDefault(r.getId(), Integer.MAX_VALUE))
                .thenComparing(byPoints));
        listeners.sort(byPoints);

        List<SearchDto.Result> ordered = new ArrayList<>(artists.size() + listeners.size());
        ordered.addAll(artists);
        ordered.addAll(listeners);
        return List.copyOf(ordered);
    }

    private static boolean isArtist(SearchDto.Result r) {
        if ("artist".equalsIgnoreCase(r.getType())) return true;
        Object role = r.getExtra() == null ? null : r.getExtra().get("role");
        return role != null && "artist".equalsIgnoreCase(role.toString());
    }

    private List<SearchDto.Result> searchAll(String query, String filterType, UUID jurisdictionId,
                                             int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("query", query)                                             // ★ was query.trim()
                .addValue("filterType", filterType)
                .addValue("jurisdictionId", jurisdictionId)
                .addValue("limit", limit)
                .addValue("offset", offset);

        String sql = "SELECT * FROM search_all(:query, :filterType, :jurisdictionId, :limit, :offset)";

        return jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            // Parse the JSONB extra field
            String extraJson = rs.getString("extra_json");
            Map<String, Object> extra = Collections.emptyMap();
            if (extraJson != null) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper =
                            new com.fasterxml.jackson.databind.ObjectMapper();
                    extra = mapper.readValue(extraJson,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    // Silently fall back to empty map
                }
            }

            return SearchDto.Result.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .name(rs.getString("name"))
                    .subtitle(rs.getString("subtitle"))
                    .type(rs.getString("type"))
                    .artworkUrl(rs.getString("artwork_url"))
                    .score(rs.getInt("score"))
                    .extra(extra)
                    .similarityScore(rs.getFloat("similarity_score"))
                    .build();
        });
    }

    /**
     * Trending songs for zero-state search display.
     * Cached for 1 minute (matches existing trending cache TTL).
     */
    @Cacheable(value = "trending", key = "'search-trending-' + #jurisdictionId")
    public List<SearchDto.TrendingItem> getTrending(UUID jurisdictionId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("jurisdictionId", jurisdictionId)
                .addValue("limit", limit);

        String sql = "SELECT * FROM search_trending(:jurisdictionId, :limit)";

        return jdbcTemplate.query(sql, params, (rs, rowNum) ->
                SearchDto.TrendingItem.builder()
                        .id(UUID.fromString(rs.getString("id")))
                        .name(rs.getString("name"))
                        .subtitle(rs.getString("subtitle"))
                        .type(rs.getString("type"))
                        .artworkUrl(rs.getString("artwork_url"))
                        .score(rs.getInt("score"))
                        .build()
        );
    }
}