package com.unis.service;

import com.unis.entity.CronExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;          // ★ piece 3
import java.util.Collections;        // ★ piece 3
import java.util.HashMap;            // ★ piece 3
import java.util.LinkedHashMap;      // ★ piece 3
import java.util.List;
import java.util.Map;                // ★ piece 3
import java.util.UUID;               // ★ piece 3

/**
 * Nightly precompute of artist Territory Rank.
 *
 * For every active artist we resolve their home jurisdiction's full ancestor
 * chain (home + all parents up to the root) and rank them, within each
 * jurisdiction in that chain, against everyone else whose home is at or below
 * that jurisdiction ("where you're from", not "where you're popular").
 *
 * Two ranks per jurisdiction:
 *   - overall  (genre_id IS NULL): every artist in the jurisdiction
 *   - category (genre_id = users.genre_id): only artists in that category
 *
 * Six period windows (today | week | month | quarter | year | all), each
 * anchored to the last COMPLETE day so a just-after-midnight snapshot is
 * meaningful. The metric and tiebreaker cascade exactly mirror AwardService:
 *   weighted_points DESC -> plays DESC -> likes DESC -> score DESC -> seniority ASC
 * where weighted_points sums each vote by the interval it was cast under
 * (Annual 250, Midterm 200, Quarterly 60, Monthly 25, Weekly 20, Daily 10).
 *
 * Scalability: ranking is O(artists) via window functions, not O(events).
 * Each window is aggregated once per night into a staging table, then swapped
 * into the live table inside one transaction so dashboard readers always see
 * the last complete snapshot (no "calculating, be ready soon" freeze).
 */
@Service
public class TerritoryRankService {

    private final JdbcTemplate jdbc;
    private final CronMonitorService cronMonitorService;

    @Lazy
    @Autowired
    private TerritoryRankService self;

    private static final ZoneId UNIS_ZONE = ZoneId.of("America/New_York");

    private static final List<String> PERIODS =
        List.of("today", "week", "month", "quarter", "year", "all");

    public TerritoryRankService(JdbcTemplate jdbc, CronMonitorService cronMonitorService) {
        this.jdbc = jdbc;
        this.cronMonitorService = cronMonitorService;
    }

    // =========================================================================
    // ENTRY POINTS
    // =========================================================================

    /**
     * Nightly job. Runs at 00:30 ET, after the 00:01 ET awards crons.
     */
    @Scheduled(cron = "0 30 0 * * ?", zone = "America/New_York")
    public void nightlyTerritoryRanks() {
        executeTracked();
    }

    /**
     * Manual trigger entry point (admin endpoint). Returns rows written to live.
     */
    public int runNow() {
        return executeTracked();
    }

    private int executeTracked() {
        CronExecution exec = cronMonitorService.startExecution("TERRITORY_RANKS");
        try {
            int rows = computeAndSwap();
            cronMonitorService.markSuccess(exec, rows);
            System.out.println("=== TERRITORY_RANKS COMPLETE: " + rows + " rank rows written ===");
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
     * Build every period's ranks into staging, then atomically swap to live.
     * Staging writes run outside the swap transaction so the swap holds the
     * ACCESS EXCLUSIVE lock for the shortest possible time.
     */
    public int computeAndSwap() {
        LocalDate ref = LocalDate.now(UNIS_ZONE).minusDays(1);

        jdbc.update("TRUNCATE jurisdiction_ranks_staging");

        for (String period : PERIODS) {
            LocalDate start = startDateFor(period, ref);
            int inserted = jdbc.update(INSERT_PERIOD_SQL, start, ref, period, period);
            System.out.println("TERRITORY_RANKS staged period=" + period
                + " window=" + start + ".." + ref + " rows=" + inserted);
        }

        return self.swapStagingToLive();
    }

    /**
     * Atomic swap: truncate live and refill from staging in one transaction.
     * Called via the self proxy so @Transactional actually applies.
     */
    @Transactional
    public int swapStagingToLive() {
        jdbc.update("TRUNCATE jurisdiction_ranks");
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
                return ref.with(DayOfWeek.MONDAY);
            case "month":
                return ref.withDayOfMonth(1);
            case "quarter":
                int q = (ref.getMonthValue() - 1) / 3;
                return ref.withMonth(q * 3 + 1).withDayOfMonth(1);
            case "year":
                return ref.withDayOfYear(1);
            case "all":
            default:
                return LocalDate.of(1970, 1, 1);
        }
    }

    // =========================================================================
    // RANK SQL
    // Positional params: (1) window start date, (2) window end date,
    //                    (3) period label for overall rows,
    //                    (4) period label for category rows.
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
        bounds AS (
            SELECT CAST(? AS date) AS start_d, CAST(? AS date) AS end_d
        ),
        vote_pts AS (
            SELECT v.target_id AS artist_id,
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
            JOIN voting_intervals vi ON v.interval_id = vi.interval_id
            CROSS JOIN bounds b
            WHERE v.target_type = 'artist'
              AND v.vote_date BETWEEN b.start_d AND b.end_d
            GROUP BY v.target_id
        ),
        play_cts AS (
            SELECT s.artist_id, COUNT(*) AS plays_count
            FROM song_plays sp
            JOIN songs s ON sp.song_id = s.song_id
            CROSS JOIN bounds b
            WHERE sp.played_at IS NOT NULL
              AND CAST(sp.played_at AS date) BETWEEN b.start_d AND b.end_d
            GROUP BY s.artist_id
        ),
        like_cts AS (
            SELECT s.artist_id, COUNT(*) AS likes_count
            FROM likes l
            JOIN songs s ON l.media_id = s.song_id
            CROSS JOIN bounds b
            WHERE l.media_type = 'song'
              AND CAST(l.created_at AS date) BETWEEN b.start_d AND b.end_d
            GROUP BY s.artist_id
        ),
        artist_scores AS (
            SELECT u.user_id          AS artist_id,
                   u.jurisdiction_id  AS home_jur,
                   u.genre_id         AS genre_id,
                   COALESCE(vp.weighted_points, 0) AS weighted_points,
                   COALESCE(pc.plays_count, 0)     AS plays_count,
                   COALESCE(lc.likes_count, 0)     AS likes_count,
                   COALESCE(u.score, 0)            AS user_score,
                   u.created_at                    AS seniority
            FROM users u
            LEFT JOIN vote_pts vp ON vp.artist_id = u.user_id
            LEFT JOIN play_cts pc ON pc.artist_id = u.user_id
            LEFT JOIN like_cts lc ON lc.artist_id = u.user_id
            WHERE u.role = 'artist'
              AND u.deleted_at IS NULL
              AND u.jurisdiction_id IS NOT NULL
        ),
        membership AS (
            SELECT jc.anc_id AS jurisdiction_id,
                   sc.artist_id,
                   sc.genre_id,
                   sc.weighted_points,
                   sc.plays_count,
                   sc.likes_count,
                   sc.user_score,
                   sc.seniority
            FROM artist_scores sc
            JOIN jur_closure jc ON jc.leaf_id = sc.home_jur
        ),
        overall_ranked AS (
            SELECT artist_id,
                   jurisdiction_id,
                   CAST(NULL AS uuid) AS genre_id,
                   weighted_points AS score,
                   ROW_NUMBER() OVER (PARTITION BY jurisdiction_id
                       ORDER BY weighted_points DESC, plays_count DESC, likes_count DESC,
                                user_score DESC, seniority ASC) AS rnk,
                   COUNT(*) OVER (PARTITION BY jurisdiction_id) AS total_in_jur
            FROM membership
        ),
        genre_ranked AS (
            SELECT artist_id,
                   jurisdiction_id,
                   genre_id,
                   weighted_points AS score,
                   ROW_NUMBER() OVER (PARTITION BY jurisdiction_id, genre_id
                       ORDER BY weighted_points DESC, plays_count DESC, likes_count DESC,
                                user_score DESC, seniority ASC) AS rnk,
                   COUNT(*) OVER (PARTITION BY jurisdiction_id, genre_id) AS total_in_jur
            FROM membership
            WHERE genre_id IS NOT NULL
        )
        INSERT INTO jurisdiction_ranks_staging
            (artist_id, jurisdiction_id, genre_id, period, rank, total_in_jurisdiction, score)
        SELECT artist_id, jurisdiction_id, genre_id, ?, rnk, total_in_jur, score
        FROM overall_ranked
        UNION ALL
        SELECT artist_id, jurisdiction_id, genre_id, ?, rnk, total_in_jur, score
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