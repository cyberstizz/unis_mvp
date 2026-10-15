package com.unis.service;

import com.unis.entity.CronExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Artist Territory Rank — a nightly snapshot per jurisdiction.
 *
 * For every voting-enabled jurisdiction, every artist whose home is that
 * jurisdiction or inside it is ranked against the others there ("where you're
 * from", not "where you're popular"). Two ranks per jurisdiction:
 *   - overall  (genre_id IS NULL): every artist in the jurisdiction
 *   - category (genre_id = users.genre_id): only artists in that category
 *
 * <p><b>Same formula as award winners</b> (RankingService):
 * total points = weighted vote points + plays x 1 + likes x 5, then vote
 * points, plays, likes, score, seniority. Votes count in a jurisdiction only
 * from voters who live there or inside it, on the voter's day; plays and likes
 * count on the artist's day.
 *
 * <p><b>Each jurisdiction on its own clock.</b> RaceCloseScheduler calls
 * {@link #refreshFor} for the jurisdictions whose day just ended, so Harlem's
 * ranks refresh after midnight New York time and a Los Angeles jurisdiction's
 * after midnight Pacific. Six windows (today | week | month | quarter | year |
 * all), each anchored to that jurisdiction's last complete day, so the number
 * never moves mid-day for reasons nobody can act on.
 *
 * <p>Each refresh is staged first, then swapped into the live table in one
 * transaction, so readers always see a complete snapshot.
 */
@Service
public class TerritoryRankService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final CronMonitorService cronMonitorService;
    private final RaceClockService raceClock;

    @Lazy
    @Autowired
    private TerritoryRankService self;

    private static final List<String> PERIODS =
        List.of("today", "week", "month", "quarter", "year", "all");

    public TerritoryRankService(JdbcTemplate jdbc, CronMonitorService cronMonitorService,
                                RaceClockService raceClock) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.cronMonitorService = cronMonitorService;
        this.raceClock = raceClock;
    }

    // =========================================================================
    // ENTRY POINTS
    // =========================================================================

    /**
     * Manual trigger (admin endpoint): refresh every voting-enabled
     * jurisdiction now. Returns rows written to live.
     */
    public int runNow() {
        List<UUID> all = jdbc.queryForList(
            "SELECT jurisdiction_id FROM jurisdictions WHERE voting_enabled = true", UUID.class);
        return refreshFor(all);
    }

    /**
     * Refresh the given jurisdictions, each anchored to its own last complete
     * day. Called by RaceCloseScheduler right after their day ends.
     */
    public synchronized int refreshFor(Collection<UUID> jurisdictionIds) {
        if (jurisdictionIds == null || jurisdictionIds.isEmpty()) return 0;
        CronExecution exec = cronMonitorService.startExecution("TERRITORY_RANKS");
        try {
            // Jurisdictions on the same clock share a last complete day, so they
            // are computed together in one pass.
            Map<LocalDate, List<UUID>> byLastCompleteDay = new TreeMap<>();
            for (UUID id : jurisdictionIds) {
                LocalDate ref = raceClock.latestClosedPeriodEnd(id, "Daily");
                byLastCompleteDay.computeIfAbsent(ref, k -> new ArrayList<>()).add(id);
            }
            int rows = 0;
            for (Map.Entry<LocalDate, List<UUID>> group : byLastCompleteDay.entrySet()) {
                rows += computeAndSwap(group.getValue(), group.getKey());
            }
            cronMonitorService.markSuccess(exec, rows);
            System.out.println("=== TERRITORY_RANKS COMPLETE: " + rows + " rank rows written for "
                + jurisdictionIds.size() + " jurisdiction(s) ===");
            return rows;
        } catch (Exception e) {
            cronMonitorService.markFailed(exec, e.getMessage());
            System.out.println("=== TERRITORY_RANKS FAILED: " + e.getMessage() + " ===");
            throw e;
        }
    }

    // =========================================================================
    // COMPUTE
    // =========================================================================

    /**
     * Stage every period's ranks for one group of jurisdictions, then swap them
     * into live. Staging writes run outside the swap transaction so the swap
     * holds its lock for the shortest possible time.
     */
    private int computeAndSwap(List<UUID> jurisdictionIds, LocalDate ref) {
        jdbc.update("TRUNCATE jurisdiction_ranks_staging");

        for (String period : PERIODS) {
            LocalDate start = startDateFor(period, ref);
            MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("races", jurisdictionIds)
                .addValue("start", start)
                .addValue("end", ref)
                .addValue("scanFrom", RaceClockService.scanFromUtc(start))
                .addValue("scanTo", RaceClockService.scanToUtc(ref))
                .addValue("period", period);
            int inserted = named.update(INSERT_PERIOD_SQL, p);
            System.out.println("TERRITORY_RANKS staged period=" + period
                + " window=" + start + ".." + ref + " jurisdictions=" + jurisdictionIds.size()
                + " rows=" + inserted);
        }

        return self.swapStagingToLive(jurisdictionIds);
    }

    /**
     * Replace these jurisdictions' live rows with the staged ones, in one
     * transaction. Rows for jurisdictions that are no longer voting-enabled are
     * cleared at the same time. Called via the self proxy so @Transactional
     * applies.
     */
    @Transactional
    public int swapStagingToLive(List<UUID> jurisdictionIds) {
        named.update(
            "DELETE FROM jurisdiction_ranks WHERE jurisdiction_id IN (:races) "
                + "OR jurisdiction_id NOT IN (SELECT jurisdiction_id FROM jurisdictions WHERE voting_enabled = true)",
            new MapSqlParameterSource("races", jurisdictionIds));
        return jdbc.update(
            "INSERT INTO jurisdiction_ranks "
            + "(artist_id, jurisdiction_id, genre_id, period, rank, total_in_jurisdiction, score) "
            + "SELECT artist_id, jurisdiction_id, genre_id, period, rank, total_in_jurisdiction, score "
            + "FROM jurisdiction_ranks_staging");
    }

    // =========================================================================
    // ★ piece 3: READ — territory rank for one artist (dashboard endpoint)
    // =========================================================================

    /**
     * ★ Returns the artist's full rank matrix: every jurisdiction in their home
     * chain (ordered neighborhood -> national) crossed with all six periods,
     * each row carrying overall rank/total and category rank/total.
     *
     * Single indexed read on jurisdiction_ranks(artist_id); the chain + names
     * come from one recursive walk. Cold start (table not yet populated)
     * returns status "calculating" with empty periods so the UI can show a
     * "ranks calculating" state instead of breaking.
     *
     * Shape:
     * {
     *   status: "ok" | "calculating",
     *   computedAt: "2026-06-11T00:30:..",
     *   defaultPeriod: "year",
     *   genreId, genreName,
     *   periods: {
     *     year: [ { jurisdictionId, jurisdictionName, depth,
     *               overallRank, overallTotal, genreRank, genreTotal }, ... ],
     *     all: [...], today: [...], week: [...], month: [...], quarter: [...]
     *   }
     * }
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getTerritoryRank(UUID artistId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("defaultPeriod", "year");

        // Artist's static category (for "· Hip-Hop" labeling).
        Map<String, Object> cat = jdbc.queryForMap(CATEGORY_SQL, artistId);
        Object genreId = cat.get("genre_id");
        result.put("genreId", genreId != null ? genreId.toString() : null);
        result.put("genreName", cat.get("genre_name"));

        // Ordered home -> root chain (lvl 0 = home/neighborhood).
        List<Map<String, Object>> chain = jdbc.queryForList(CHAIN_SQL, artistId);

        // Every rank row for this artist, in one indexed lookup.
        List<Map<String, Object>> rankRows = jdbc.queryForList(RANK_ROWS_SQL, artistId);

        if (rankRows.isEmpty()) {
            result.put("status", "calculating");
            result.put("periods", new LinkedHashMap<>());
            return result;
        }

        // Index rank rows: period -> jurisdictionId -> { overall, genre }.
        Map<String, Map<String, Map<String, Map<String, Object>>>> idx = new HashMap<>();
        String computedAt = null;
        for (Map<String, Object> r : rankRows) {
            String period = (String) r.get("period");
            String jurId = r.get("jurisdiction_id").toString();
            String kind = r.get("genre_id") != null ? "genre" : "overall";
            idx.computeIfAbsent(period, k -> new HashMap<>())
               .computeIfAbsent(jurId, k -> new HashMap<>())
               .put(kind, r);
            if (computedAt == null && r.get("computed_at") != null) {
                computedAt = toIso(r.get("computed_at"));     // ★ piece 3: ISO-8601
            }
        }

        result.put("status", "ok");
        result.put("computedAt", computedAt);

        Map<String, Object> periods = new LinkedHashMap<>();
        for (String period : PERIODS) {
            Map<String, Map<String, Map<String, Object>>> byJur =
                idx.getOrDefault(period, Collections.emptyMap());

            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> c : chain) {
                String jurId = c.get("jurisdiction_id").toString();
                Map<String, Map<String, Object>> pair = byJur.get(jurId);
                Map<String, Object> overall = pair != null ? pair.get("overall") : null;
                Map<String, Object> genre = pair != null ? pair.get("genre") : null;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("jurisdictionId", jurId);
                row.put("jurisdictionName", c.get("name"));
                row.put("depth", toInt(c.get("lvl")));
                row.put("overallRank", overall != null ? toInt(overall.get("rank")) : null);
                row.put("overallTotal", overall != null ? toInt(overall.get("total_in_jurisdiction")) : null);
                row.put("genreRank", genre != null ? toInt(genre.get("rank")) : null);
                row.put("genreTotal", genre != null ? toInt(genre.get("total_in_jurisdiction")) : null);
                rows.add(row);
            }
            periods.put(period, rows);
        }
        result.put("periods", periods);
        return result;
    }

    private static Integer toInt(Object o) {                          // ★ piece 3
        return o == null ? null : ((Number) o).intValue();
    }

    private static String toIso(Object o) {                           // ★ piece 3: ISO-8601 computedAt
        if (o == null) return null;
        if (o instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) o).toLocalDateTime().toString();
        }
        return o.toString();
    }

    // =========================================================================
    // WINDOW START DATES (anchored to the last complete day = ref)
    // =========================================================================

    private LocalDate startDateFor(String period, LocalDate ref) {
        switch (period) {
            case "today":
                return ref;
            case "week":
                return RaceClockService.periodContaining("Weekly", ref)[0];
            case "month":
                return RaceClockService.periodContaining("Monthly", ref)[0];
            case "quarter":
                return RaceClockService.periodContaining("Quarterly", ref)[0];
            case "year":
                return RaceClockService.periodContaining("Annual", ref)[0];
            case "all":
            default:
                return LocalDate.of(1970, 1, 1);
        }
    }

    // =========================================================================
    // RANK SQL — the RankingService formula, for every artist and every
    // jurisdiction in :races at once.
    //   :races     jurisdictions being refreshed
    //   :start/:end  the window, as local calendar days
    //   :scanFrom/:scanTo  UTC pre-filter for the timestamp scans
    //   :period    label written on every row
    // =========================================================================

    private static final String INSERT_PERIOD_SQL = """
        WITH RECURSIVE jur_closure AS (
            SELECT jurisdiction_id AS leaf_id, jurisdiction_id AS anc_id
            FROM jurisdictions
            UNION ALL
            SELECT jc.leaf_id, j.parent_jurisdiction_id
            FROM jur_closure jc
            JOIN jurisdictions j ON j.jurisdiction_id = jc.anc_id
            WHERE j.parent_jurisdiction_id IS NOT NULL
        ),
        vote_pts AS (
            -- A vote counts in a jurisdiction only when the voter lives there
            -- or inside it; local ranks are decided by local voters.
            SELECT v.target_id AS artist_id,
                   vc.anc_id   AS jurisdiction_id,
                   SUM(CASE vi.name
                         WHEN 'Annual'    THEN 250
                         WHEN 'Midterm'   THEN 200
                         WHEN 'Quarterly' THEN 60
                         WHEN 'Monthly'   THEN 25
                         WHEN 'Weekly'    THEN 20
                         WHEN 'Daily'     THEN 10
                         ELSE 0
                       END) AS weighted_points
            FROM votes v
            JOIN voting_intervals vi ON vi.interval_id = v.interval_id
            JOIN users voter ON voter.user_id = v.user_id
            JOIN jur_closure vc ON vc.leaf_id = voter.jurisdiction_id
            WHERE v.target_type = 'artist'
              AND v.vote_date BETWEEN :start AND :end
              AND voter.deleted_at IS NULL
              AND vc.anc_id IN (:races)
            GROUP BY v.target_id, vc.anc_id
        ),
        play_cts AS (
            -- Plays count on the calendar day of the artist home clock.
            SELECT s.artist_id, COUNT(*) AS plays_count
            FROM song_plays sp
            JOIN songs s ON s.song_id = sp.song_id
            JOIN users a ON a.user_id = s.artist_id
            JOIN jurisdictions aj ON aj.jurisdiction_id = a.jurisdiction_id
            LEFT JOIN users listener ON listener.user_id = sp.user_id
            WHERE sp.played_at >= :scanFrom AND sp.played_at < :scanTo
              AND CAST((CAST(sp.played_at AS timestamp) AT TIME ZONE 'UTC') AT TIME ZONE aj.timezone AS date)
                  BETWEEN :start AND :end
              AND s.deleted_at IS NULL
              AND listener.deleted_at IS NULL
            GROUP BY s.artist_id
        ),
        like_cts AS (
            SELECT s.artist_id, COUNT(*) AS likes_count
            FROM likes l
            JOIN songs s ON s.song_id = l.media_id
            JOIN users a ON a.user_id = s.artist_id
            JOIN jurisdictions aj ON aj.jurisdiction_id = a.jurisdiction_id
            WHERE l.media_type = 'song'
              AND l.created_at >= :scanFrom AND l.created_at < :scanTo
              AND CAST((CAST(l.created_at AS timestamp) AT TIME ZONE 'UTC') AT TIME ZONE aj.timezone AS date)
                  BETWEEN :start AND :end
              AND s.deleted_at IS NULL
            GROUP BY s.artist_id
        ),
        artist_scores AS (
            SELECT u.user_id                       AS artist_id,
                   u.jurisdiction_id               AS home_jur,
                   u.genre_id                      AS genre_id,
                   COALESCE(pc.plays_count, 0)     AS plays_count,
                   COALESCE(lc.likes_count, 0)     AS likes_count,
                   COALESCE(u.score, 0)            AS user_score,
                   u.created_at                    AS seniority
            FROM users u
            LEFT JOIN play_cts pc ON pc.artist_id = u.user_id
            LEFT JOIN like_cts lc ON lc.artist_id = u.user_id
            WHERE u.role = 'artist'
              AND u.deleted_at IS NULL
              AND u.jurisdiction_id IS NOT NULL
        ),
        membership AS (
            SELECT jc.anc_id                                   AS jurisdiction_id,
                   sc.artist_id,
                   sc.genre_id,
                   COALESCE(vp.weighted_points, 0)             AS weighted_points,
                   sc.plays_count,
                   sc.likes_count,
                   sc.user_score,
                   sc.seniority,
                   COALESCE(vp.weighted_points, 0)
                     + sc.plays_count * 1
                     + sc.likes_count * 5                      AS total_points
            FROM artist_scores sc
            JOIN jur_closure jc ON jc.leaf_id = sc.home_jur
            LEFT JOIN vote_pts vp ON vp.artist_id = sc.artist_id
                                 AND vp.jurisdiction_id = jc.anc_id
            WHERE jc.anc_id IN (:races)
        ),
        overall_ranked AS (
            SELECT artist_id,
                   jurisdiction_id,
                   CAST(NULL AS uuid) AS genre_id,
                   total_points AS score,
                   ROW_NUMBER() OVER (PARTITION BY jurisdiction_id
                       ORDER BY total_points DESC, weighted_points DESC, plays_count DESC,
                                likes_count DESC, user_score DESC, seniority ASC, artist_id ASC) AS rnk,
                   COUNT(*) OVER (PARTITION BY jurisdiction_id) AS total_in_jur
            FROM membership
        ),
        genre_ranked AS (
            SELECT artist_id,
                   jurisdiction_id,
                   genre_id,
                   total_points AS score,
                   ROW_NUMBER() OVER (PARTITION BY jurisdiction_id, genre_id
                       ORDER BY total_points DESC, weighted_points DESC, plays_count DESC,
                                likes_count DESC, user_score DESC, seniority ASC, artist_id ASC) AS rnk,
                   COUNT(*) OVER (PARTITION BY jurisdiction_id, genre_id) AS total_in_jur
            FROM membership
            WHERE genre_id IS NOT NULL
        )
        INSERT INTO jurisdiction_ranks_staging
            (artist_id, jurisdiction_id, genre_id, period, rank, total_in_jurisdiction, score)
        SELECT artist_id, jurisdiction_id, genre_id, :period, rnk, total_in_jur, score
        FROM overall_ranked
        UNION ALL
        SELECT artist_id, jurisdiction_id, genre_id, :period, rnk, total_in_jur, score
        FROM genre_ranked
        """;

    // ★ piece 3: artist's static category
    private static final String CATEGORY_SQL = """
        SELECT u.genre_id AS genre_id, g.name AS genre_name
        FROM users u
        LEFT JOIN genres g ON g.genre_id = u.genre_id
        WHERE u.user_id = ?
        """;

    // ★ ordered home -> root chain (lvl 0 = home/neighborhood).
    // Walks the FULL chain, then returns only ACTIVE (voting_enabled) jurisdictions,
    // so the dashboard shows just the launched levels (e.g. Uptown Harlem + Harlem).
    // Higher levels appear automatically once their voting_enabled flag is set true.
    private static final String CHAIN_SQL = """
        WITH RECURSIVE chain AS (
            SELECT j.jurisdiction_id, j.name, j.parent_jurisdiction_id, j.voting_enabled, 0 AS lvl
            FROM users u
            JOIN jurisdictions j ON j.jurisdiction_id = u.jurisdiction_id
            WHERE u.user_id = ?
            UNION ALL
            SELECT p.jurisdiction_id, p.name, p.parent_jurisdiction_id, p.voting_enabled, c.lvl + 1
            FROM chain c
            JOIN jurisdictions p ON p.jurisdiction_id = c.parent_jurisdiction_id
        )
        SELECT jurisdiction_id, name, lvl
        FROM chain
        WHERE voting_enabled = true
        ORDER BY lvl ASC
        """;

    // ★ piece 3: every rank row for one artist (indexed on artist_id)
    private static final String RANK_ROWS_SQL = """
        SELECT jurisdiction_id, genre_id, period, rank, total_in_jurisdiction, score, computed_at
        FROM jurisdiction_ranks
        WHERE artist_id = ?
        """;
}