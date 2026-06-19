package com.unis.service;

import com.unis.dto.SearchDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    public SearchService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("query", normalizedQuery)                                   // ★ was query.trim()
                .addValue("filterType", type != null ? type : "all")
                .addValue("jurisdictionId", jurisdictionId)
                .addValue("limit", limit)
                .addValue("offset", offset);

        String sql = "SELECT * FROM search_all(:query, :filterType, :jurisdictionId, :limit, :offset)";

        List<SearchDto.Result> results = jdbcTemplate.query(sql, params, (rs, rowNum) -> {
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