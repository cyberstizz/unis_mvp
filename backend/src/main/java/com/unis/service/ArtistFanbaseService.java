package com.unis.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Artist-facing fanbase analytics for the Artist Dashboard.
 *
 * Distinct from the platform/admin AnalyticsService (DAU/MAU/DMCA/referrals).
 *
 * All queries run against existing tables (song_plays, songs, likes, votes,
 * follows, supporters, users, jurisdictions). No migration needed.
 *
 * ★ item 5: the artist funnel now accepts optional demographic + geographic
 * drill-down filters (gender, age bucket, home jurisdiction). They compose
 * into ONE cohort sub-select that drops into every stage identically, so the
 * full funnel can be sliced by, e.g., "non-binary, 35-44, Harlem" at once.
 * Named-supporter list and 30-day growth remain all-time / fixed-window.
 */
@Service
public class ArtistFanbaseService {

    private final JdbcTemplate jdbc;

    public ArtistFanbaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final List<String> VALID_PERIODS =
        List.of("today", "week", "month", "quarter", "year", "all");

    // ★ item 5: composable drill-down filters on the listener/actor user.
    public static class Filters {
        String gender;
        boolean genderUnknown;
        Integer ageMin;
        Integer ageMax;
        boolean ageUnknown;
        UUID jurisdictionId;

        boolean active() {
            return genderUnknown || gender != null
                || ageUnknown || ageMin != null || ageMax != null
                || jurisdictionId != null;
        }
    }

    // ★ item 5: build Filters from raw request params (parsing centralized).
    private Filters parseFilters(String gender, String ageBucket, UUID jurisdictionId) {
        Filters f = new Filters();

        if (gender != null && !gender.isBlank() && !"all".equalsIgnoreCase(gender)) {
            if ("unknown".equalsIgnoreCase(gender)) {
                f.genderUnknown = true;
            } else {
                f.gender = gender;
            }
        }

        if (ageBucket != null && !ageBucket.isBlank() && !"all".equalsIgnoreCase(ageBucket)) {
            switch (ageBucket) {
                case "unknown": f.ageUnknown = true; break;
                case "13-17":   f.ageMin = 13; f.ageMax = 17; break;
                case "18-24":   f.ageMin = 18; f.ageMax = 24; break;
                case "25-34":   f.ageMin = 25; f.ageMax = 34; break;
                case "35-44":   f.ageMin = 35; f.ageMax = 44; break;
                case "45+":     f.ageMin = 45; break;
                default:        break;
            }
        }

        f.jurisdictionId = jurisdictionId;
        return f;
    }

    // ★ item 5: cohort predicate appended to any stage. Empty (and no params)
    // when no filter is active, so unfiltered behavior is byte-for-byte the
    // original. Geography here is HOME jurisdiction (users.jurisdiction_id).
    private String cohort(Filters f, String userCol, List<Object> params) {
        if (f == null || !f.active()) return "";

        List<String> preds = new ArrayList<>();

        if (f.genderUnknown) {
            preds.add("u2.gender IS NULL");
        } else if (f.gender != null) {
            preds.add("LOWER(u2.gender) = LOWER(?)");
            params.add(f.gender);
        }

        if (f.ageUnknown) {
            preds.add("u2.date_of_birth IS NULL");
        } else if (f.ageMin != null || f.ageMax != null) {
            preds.add("u2.date_of_birth IS NOT NULL");
            if (f.ageMin != null) {
                preds.add("date_part('year', age(u2.date_of_birth)) >= ?");
                params.add(f.ageMin);
            }
            if (f.ageMax != null) {
                preds.add("date_part('year', age(u2.date_of_birth)) <= ?");
                params.add(f.ageMax);
            }
        }

        if (f.jurisdictionId != null) {
            preds.add("u2.jurisdiction_id = ?");
            params.add(f.jurisdictionId);
        }

        if (preds.isEmpty()) return "";
        return " AND " + userCol + " IN (SELECT u2.user_id FROM users u2 WHERE "
            + String.join(" AND ", preds) + ")";
    }

    /**
     * @param artistId       the artist
     * @param period         today|week|month|year|all (defaults to all)
     * @param gender         null|all|male|female|...|unknown (★ item 5d)
     * @param ageBucket      null|all|13-17|18-24|25-34|35-44|45+|unknown (★ item 5d)
     * @param jurisdictionId optional home-jurisdiction filter (★ item 5e)
     */
public Map<String, Object> getArtistFanbase(
            UUID artistId, String period, String gender, String ageBucket, UUID jurisdictionId) {

        String p = (period == null || !VALID_PERIODS.contains(period)) ? "all" : period;
        Filters f = parseFilters(gender, ageBucket, jurisdictionId);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime currentStart = null;
        LocalDateTime prevStart = null;

        switch (p) {
            case "today":
                currentStart = LocalDate.now().atStartOfDay();
                prevStart = currentStart.minusDays(1);
                break;
            case "week":
                currentStart = now.minusDays(7);
                prevStart = now.minusDays(14);
                break;
            case "month":
                currentStart = now.minusDays(30);
                prevStart = now.minusDays(60);
                break;
            case "year":
                currentStart = now.minusDays(365);
                prevStart = now.minusDays(730);
                break;
            case "all":
            default:
                break;
        }

        boolean scoped = currentStart != null;

        long totalPlays = playsCount(artistId, currentStart, now, scoped, f);
        long listeners  = listenersCount(artistId, currentStart, now, scoped, f);
        long likers     = likersCount(artistId, currentStart, now, scoped, f);
        long voters     = votersCount(artistId, currentStart, now, scoped, f);
        long followers  = followersCount(artistId, currentStart, now, scoped, f);
        long supporters = supportersCount(artistId, currentStart, now, scoped, f);

        Long prevPlays = null, prevListeners = null, prevLikers = null,
             prevVoters = null, prevFollowers = null, prevSupporters = null;

        if (scoped) {
            prevPlays      = playsCount(artistId, prevStart, currentStart, true, f);
            prevListeners  = listenersCount(artistId, prevStart, currentStart, true, f);
            prevLikers     = likersCount(artistId, prevStart, currentStart, true, f);
            prevVoters     = votersCount(artistId, prevStart, currentStart, true, f);
            prevFollowers  = followersCount(artistId, prevStart, currentStart, true, f);
            prevSupporters = supportersCount(artistId, prevStart, currentStart, true, f);
        }

        List<Map<String, Object>> funnel = new ArrayList<>();
        funnel.add(stage("plays", "Plays", totalPlays, prevPlays));
        funnel.add(stage("listeners", "Listeners", listeners, prevListeners));
        funnel.add(stage("likers", "Likers", likers, prevLikers));
        funnel.add(stage("voters", "Voters", voters, prevVoters));
        funnel.add(stage("followers", "Followers", followers, prevFollowers));
        funnel.add(stage("supporters", "Supporters", supporters, prevSupporters));

        double repeatListenRatio = listeners > 0
            ? Math.round(((double) totalPlays / listeners) * 100.0) / 100.0
            : 0.0;

        // ★ item 5: advanced metrics promoted to artist level (period + cohort
        // scoped, same definitions as the per-song modal but across all songs).
        Map<String, Object> completion = artistCompletion(artistId, currentStart, now, scoped, f);
        List<Map<String, Object>> sources = artistSources(artistId, currentStart, now, scoped, f);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", p);
        result.put("funnel", funnel);
        result.put("totalPlays", totalPlays);
        result.put("uniqueListeners", listeners);
        result.put("repeatListenRatio", repeatListenRatio);
        result.put("completion", completion);    // ★ advanced
        result.put("sources", sources);           // ★ advanced
        // ★ item 5e: home jurisdictions of this artist's listeners → dropdown
        result.put("availableJurisdictions", availableJurisdictions(artistId));
        // ★ supporters split to /supporters — no longer returned here.
        return result;
    }

    // -----------------------------------------------------------------------
    // ★ item 5: per-stage counts now append (optional) date window + cohort.
    // -----------------------------------------------------------------------

    private long playsCount(UUID artistId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) FROM song_plays sp " +
            "JOIN songs s ON s.song_id = sp.song_id WHERE s.artist_id = ?");
        params.add(artistId);
        if (scoped) {
            sql.append(" AND sp.played_at >= ? AND sp.played_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(cohort(f, "sp.user_id", params));
        return count(sql.toString(), params.toArray());
    }

    private long listenersCount(UUID artistId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(DISTINCT sp.user_id) FROM song_plays sp " +
            "JOIN songs s ON s.song_id = sp.song_id " +
            "WHERE s.artist_id = ? AND sp.user_id IS NOT NULL");
        params.add(artistId);
        if (scoped) {
            sql.append(" AND sp.played_at >= ? AND sp.played_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(cohort(f, "sp.user_id", params));
        return count(sql.toString(), params.toArray());
    }

    private long likersCount(UUID artistId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(DISTINCT l.user_id) FROM likes l " +
            "JOIN songs s ON s.song_id = l.media_id " +
            "WHERE s.artist_id = ? AND l.user_id IS NOT NULL");
        params.add(artistId);
        if (scoped) {
            sql.append(" AND l.created_at >= ? AND l.created_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(cohort(f, "l.user_id", params));
        return count(sql.toString(), params.toArray());
    }

    private long votersCount(UUID artistId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(DISTINCT v.user_id) FROM votes v " +
            "WHERE (v.target_id = ? " +
            "   OR v.target_id IN (SELECT song_id FROM songs WHERE artist_id = ?))");
        params.add(artistId);
        params.add(artistId);
        if (scoped) {
            sql.append(" AND v.created_at >= ? AND v.created_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(cohort(f, "v.user_id", params));
        return count(sql.toString(), params.toArray());
    }

    // ★ follows.created_at is nullable. When scoped, null dates can't be
    // attributed to a window, so they're excluded; all-time keeps everything.
    private long followersCount(UUID artistId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) FROM follows WHERE followed_id = ?");
        params.add(artistId);
        if (scoped) {
            sql.append(" AND created_at IS NOT NULL AND created_at >= ? AND created_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(cohort(f, "follower_id", params));
        return count(sql.toString(), params.toArray());
    }

    private long supportersCount(UUID artistId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) FROM supporters WHERE artist_id = ?");
        params.add(artistId);
        if (scoped) {
            sql.append(" AND created_at IS NOT NULL AND created_at >= ? AND created_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(cohort(f, "listener_id", params));
        return count(sql.toString(), params.toArray());
    }

    // ★ item 5f: #1 supporter — must be an actual supporter (the supporters
    // join guarantees it), ranked by how many times they've played this
    // artist's songs. All-time. Ties broken by earliest support date (loyalty).
    private Map<String, Object> topSupporter(UUID artistId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT sup.listener_id AS \"userId\", u.username, u.photo_url AS \"photoUrl\", " +
            "       sup.created_at AS \"since\", COUNT(sp.play_id) AS plays " +
            "FROM supporters sup " +
            "JOIN users u ON u.user_id = sup.listener_id " +
            "LEFT JOIN song_plays sp ON sp.user_id = sup.listener_id " +
            "  AND sp.song_id IN (SELECT song_id FROM songs WHERE artist_id = ?) " +
            "WHERE sup.artist_id = ? AND u.deleted_at IS NULL " +
            "GROUP BY sup.listener_id, u.username, u.photo_url, sup.created_at " +
            "ORDER BY plays DESC, sup.created_at ASC " +
            "LIMIT 1",
            artistId, artistId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ★ item 5e: home jurisdictions among this artist's listeners (unfiltered
    // so the dropdown stays stable as the artist drills down).
    private List<Map<String, Object>> availableJurisdictions(UUID artistId) {
        return jdbc.queryForList(
            "SELECT DISTINCT u.jurisdiction_id AS id, j.name " +
            "FROM song_plays sp " +
            "JOIN songs s ON s.song_id = sp.song_id " +
            "JOIN users u ON u.user_id = sp.user_id " +
            "JOIN jurisdictions j ON j.jurisdiction_id = u.jurisdiction_id " +
            "WHERE s.artist_id = ? AND u.jurisdiction_id IS NOT NULL " +
            "ORDER BY j.name ASC",
            artistId);
    }

    // ★ item 5: supporters split into their own all-time payload (not
    // period/cohort scoped — supporters are a fixed community, not a window).
    public Map<String, Object> getArtistSupporters(UUID artistId) {
        long supportersCount = count(
            "SELECT COUNT(*) FROM supporters WHERE artist_id = ?", artistId);

        Map<String, Object> top = topSupporter(artistId);

        List<Map<String, Object>> recentSupporters = jdbc.queryForList(
            "SELECT sup.listener_id AS \"userId\", u.username, u.photo_url AS \"photoUrl\", " +
            "       sup.created_at AS \"since\" " +
            "FROM supporters sup " +
            "JOIN users u ON u.user_id = sup.listener_id " +
            "WHERE sup.artist_id = ? AND u.deleted_at IS NULL " +
            "ORDER BY sup.created_at DESC " +
            "LIMIT 12",
            artistId);

        List<Map<String, Object>> supporterGrowth = jdbc.queryForList(
            "SELECT created_at::date AS day, COUNT(*) AS count " +
            "FROM supporters " +
            "WHERE artist_id = ? AND created_at >= NOW() - INTERVAL '30 days' " +
            "GROUP BY created_at::date " +
            "ORDER BY day ASC",
            artistId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supportersCount", supportersCount);
        result.put("topSupporter", top);
        result.put("recentSupporters", recentSupporters);
        result.put("supporterGrowth", supporterGrowth);
        return result;
    }

    // ★ item 5: artist-level completion quality (period + cohort scoped),
    // aggregated across all of the artist's songs.
    private Map<String, Object> artistCompletion(
            UUID artistId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) FILTER (WHERE sp.completed = true) AS completed_plays, " +
            "       COUNT(*) AS total_plays, " +
            "       COALESCE(AVG(sp.percent_played), 0) AS avg_percent " +
            "FROM song_plays sp JOIN songs s ON s.song_id = sp.song_id " +
            "WHERE s.artist_id = ?");
        params.add(artistId);
        if (scoped) {
            sql.append(" AND sp.played_at >= ? AND sp.played_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(cohort(f, "sp.user_id", params));

        Map<String, Object> row = jdbc.queryForMap(sql.toString(), params.toArray());
        long completed = ((Number) row.getOrDefault("completed_plays", 0L)).longValue();
        long total = ((Number) row.getOrDefault("total_plays", 0L)).longValue();
        double avgPercent = ((Number) row.getOrDefault("avg_percent", 0)).doubleValue();
        double completionRate = total > 0
            ? Math.round(((double) completed / total) * 1000.0) / 10.0
            : 0.0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("completedPlays", completed);
        m.put("totalPlays", total);
        m.put("completionRate", completionRate);
        m.put("avgPercent", avgPercent);
        return m;
    }

    // ★ item 5: artist-level discovery-source breakdown (period + cohort scoped).
    private List<Map<String, Object>> artistSources(
            UUID artistId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COALESCE(sp.source, 'unknown') AS source, COUNT(*) AS count " +
            "FROM song_plays sp JOIN songs s ON s.song_id = sp.song_id " +
            "WHERE s.artist_id = ?");
        params.add(artistId);
        if (scoped) {
            sql.append(" AND sp.played_at >= ? AND sp.played_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(cohort(f, "sp.user_id", params));
        sql.append(" GROUP BY COALESCE(sp.source, 'unknown') ORDER BY count DESC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }


    // =======================================================================
    // ★ advanced: completion quality for one song (now period + cohort scoped).
    // =======================================================================
    private Map<String, Object> songCompletion(UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) FILTER (WHERE completed = true) AS completed_plays, " +
            "       COUNT(*) AS total_plays, " +
            "       COALESCE(AVG(percent_played), 0) AS avg_percent " +
            "FROM song_plays WHERE song_id = ?");
        params.add(songId);
        if (scoped) {
            sql.append(" AND played_at >= ? AND played_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(cohort(f, "user_id", params));

        Map<String, Object> row = jdbc.queryForMap(sql.toString(), params.toArray());
        long completed = ((Number) row.getOrDefault("completed_plays", 0L)).longValue();
        long total = ((Number) row.getOrDefault("total_plays", 0L)).longValue();
        double avgPercent = ((Number) row.getOrDefault("avg_percent", 0)).doubleValue();
        double completionRate = total > 0
            ? Math.round(((double) completed / total) * 1000.0) / 10.0
            : 0.0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("completedPlays", completed);
        m.put("totalPlays", total);
        m.put("completionRate", completionRate);
        m.put("avgPercent", avgPercent);
        return m;
    }

    // ★ advanced: discovery-source breakdown for one song (now period + cohort scoped).
    private List<Map<String, Object>> songSources(UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COALESCE(source, 'unknown') AS source, COUNT(*) AS count " +
            "FROM song_plays WHERE song_id = ?");
        params.add(songId);
        if (scoped) {
            sql.append(" AND played_at >= ? AND played_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(cohort(f, "user_id", params));
        sql.append(" GROUP BY COALESCE(source, 'unknown') ORDER BY count DESC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    // =======================================================================
    // ★ sales: per-song sales summary + daily time-series. (UNCHANGED)
    // =======================================================================
    public Map<String, Object> getSongSales(UUID artistId, UUID songId) {
        Long owns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM songs WHERE song_id = ? AND artist_id = ?",
            Long.class, songId, artistId);
        if (owns == null || owns == 0) {
            return null;
        }

        Map<String, Object> summary = jdbc.queryForMap(
            "SELECT COUNT(*) AS copies, " +
            "       COALESCE(SUM(amount), 0) AS gross_cents, " +
            "       COALESCE(SUM(amount - platform_fee), 0) AS net_cents " +
            "FROM purchases " +
            "WHERE song_id = ? AND artist_id = ? AND status = 'completed'",
            songId, artistId);

        List<Map<String, Object>> series = jdbc.queryForList(
            "SELECT created_at::date AS day, COUNT(*) AS copies, " +
            "       COALESCE(SUM(amount), 0) AS gross_cents, " +
            "       COALESCE(SUM(amount - platform_fee), 0) AS net_cents " +
            "FROM purchases " +
            "WHERE song_id = ? AND artist_id = ? AND status = 'completed' " +
            "GROUP BY created_at::date ORDER BY day ASC",
            songId, artistId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("songId", songId.toString());
        result.put("copies", ((Number) summary.getOrDefault("copies", 0L)).longValue());
        result.put("grossCents", ((Number) summary.getOrDefault("gross_cents", 0L)).longValue());
        result.put("netCents", ((Number) summary.getOrDefault("net_cents", 0L)).longValue());
        result.put("series", series);
        return result;
    }

    public Map<String, Object> getArtistSalesTotal(UUID artistId) { // ★ artist-level sales aggregate (all songs)
    Map<String, Object> summary = jdbc.queryForMap(
        "SELECT COUNT(*) AS copies, " +
        "       COALESCE(SUM(amount), 0) AS gross_cents, " +
        "       COALESCE(SUM(amount - platform_fee), 0) AS net_cents " +
        "FROM purchases " +
        "WHERE artist_id = ? AND status = 'completed'",
        artistId);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("copies", ((Number) summary.getOrDefault("copies", 0L)).longValue());
    result.put("grossCents", ((Number) summary.getOrDefault("gross_cents", 0L)).longValue());
    result.put("netCents", ((Number) summary.getOrDefault("net_cents", 0L)).longValue());
    return result;
}

    private Map<String, Object> stage(String key, String label, long value, Long prevValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("value", value);
        if (prevValue != null) {
            m.put("previous", prevValue);
            m.put("delta", value - prevValue);
        } else {
            m.put("previous", null);
            m.put("delta", null);
        }
        return m;
    }

    private long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0L : v;
    }


    // =======================================================================
    // ★ per-song funnel — now a twin of getArtistFanbase, scoped to ONE song.
    // Adds the Plays stage at the top and the same gender/age/location cohort
    // filtering. The 3-arg overload below keeps existing callers/tests working.
    // =======================================================================
    public Map<String, Object> getSongFunnel(UUID artistId, UUID songId, String period) {
        return getSongFunnel(artistId, songId, period, null, null, null);
    }

    public Map<String, Object> getSongFunnel(
            UUID artistId, UUID songId, String period,
            String gender, String ageBucket, UUID jurisdictionId) {

        Long owns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM songs WHERE song_id = ? AND artist_id = ?",
            Long.class, songId, artistId);
        if (owns == null || owns == 0) {
            return null;
        }

        String p = (period == null || !VALID_PERIODS.contains(period)) ? "all" : period;
        Filters f = parseFilters(gender, ageBucket, jurisdictionId);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime currentStart = null;
        LocalDateTime prevStart = null;

        switch (p) {
            case "today":
                currentStart = LocalDate.now().atStartOfDay();
                prevStart = currentStart.minusDays(1);
                break;
            case "week":
                currentStart = now.minusDays(7);
                prevStart = now.minusDays(14);
                break;
            case "month":
                currentStart = now.minusDays(30);
                prevStart = now.minusDays(60);
                break;
            case "quarter":
                currentStart = now.minusDays(90);
                prevStart = now.minusDays(180);
                break;
            case "year":
                currentStart = now.minusDays(365);
                prevStart = now.minusDays(730);
                break;
            case "all":
            default:
                break;
        }
        boolean scoped = currentStart != null;

        long totalPlays = songPlaysCount(songId, currentStart, now, scoped, f);
        long listeners  = songListenersCount(songId, currentStart, now, scoped, f);
        long likers     = songLikersCount(songId, currentStart, now, scoped, f);
        long voters     = songVotersCount(songId, currentStart, now, scoped, f);
        long followers  = songFollowersCount(artistId, songId, currentStart, now, scoped, f);
        long supporters = songSupportersCount(artistId, songId, currentStart, now, scoped, f);

        Long pP = null, pL = null, pLk = null, pV = null, pF = null, pS = null;
        if (scoped) {
            pP  = songPlaysCount(songId, prevStart, currentStart, true, f);
            pL  = songListenersCount(songId, prevStart, currentStart, true, f);
            pLk = songLikersCount(songId, prevStart, currentStart, true, f);
            pV  = songVotersCount(songId, prevStart, currentStart, true, f);
            pF  = songFollowersCount(artistId, songId, prevStart, currentStart, true, f);
            pS  = songSupportersCount(artistId, songId, prevStart, currentStart, true, f);
        }

        List<Map<String, Object>> funnel = new ArrayList<>();
        funnel.add(stage("plays", "Plays", totalPlays, pP));
        funnel.add(stage("listeners", "Listeners", listeners, pL));
        funnel.add(stage("likers", "Likers", likers, pLk));
        funnel.add(stage("voters", "Voters", voters, pV));
        funnel.add(stage("followers", "Followers", followers, pF));
        funnel.add(stage("supporters", "Supporters", supporters, pS));

        double repeatListenRatio = listeners > 0
            ? Math.round(((double) totalPlays / listeners) * 100.0) / 100.0
            : 0.0;

        Map<String, Object> completion = songCompletion(songId, currentStart, now, scoped, f);
        List<Map<String, Object>> sources = songSources(songId, currentStart, now, scoped, f);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", p);
        result.put("songId", songId.toString());
        result.put("funnel", funnel);
        result.put("totalPlays", totalPlays);
        result.put("uniqueListeners", listeners);
        result.put("repeatListenRatio", repeatListenRatio);
        result.put("completion", completion);
        result.put("sources", sources);
        result.put("availableJurisdictions", songAvailableJurisdictions(songId));
        return result;
    }

    // ★ home jurisdictions among this song's listeners → location dropdown.
    private List<Map<String, Object>> songAvailableJurisdictions(UUID songId) {
        return jdbc.queryForList(
            "SELECT DISTINCT u.jurisdiction_id AS id, j.name " +
            "FROM song_plays sp " +
            "JOIN users u ON u.user_id = sp.user_id " +
            "JOIN jurisdictions j ON j.jurisdiction_id = u.jurisdiction_id " +
            "WHERE sp.song_id = ? AND u.jurisdiction_id IS NOT NULL " +
            "ORDER BY j.name ASC",
            songId);
    }

    private long songPlaysCount(UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM song_plays WHERE song_id = ?");
        params.add(songId);
        if (scoped) {
            sql.append(" AND played_at >= ? AND played_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(cohort(f, "user_id", params));
        return count(sql.toString(), params.toArray());
    }

    private long songListenersCount(UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(DISTINCT user_id) FROM song_plays WHERE song_id = ? AND user_id IS NOT NULL");
        params.add(songId);
        if (scoped) {
            sql.append(" AND played_at >= ? AND played_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(cohort(f, "user_id", params));
        return count(sql.toString(), params.toArray());
    }

    private long songLikersCount(UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(DISTINCT user_id) FROM likes WHERE media_id = ? AND user_id IS NOT NULL");
        params.add(songId);
        if (scoped) {
            sql.append(" AND created_at >= ? AND created_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(cohort(f, "user_id", params));
        return count(sql.toString(), params.toArray());
    }

    private long songVotersCount(UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(DISTINCT user_id) FROM votes WHERE target_id = ?");
        params.add(songId);
        if (scoped) {
            sql.append(" AND created_at >= ? AND created_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(cohort(f, "user_id", params));
        return count(sql.toString(), params.toArray());
    }

    private long songFollowersCount(UUID artistId, UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(DISTINCT fo.follower_id) FROM follows fo " +
            "WHERE fo.followed_id = ? AND fo.follower_id IN (" +
            "  SELECT sp.user_id FROM song_plays sp WHERE sp.song_id = ? AND sp.user_id IS NOT NULL");
        params.add(artistId);
        params.add(songId);
        if (scoped) {
            sql.append(" AND sp.played_at >= ? AND sp.played_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(")");
        sql.append(cohort(f, "fo.follower_id", params));
        return count(sql.toString(), params.toArray());
    }

    private long songSupportersCount(UUID artistId, UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped, Filters f) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(DISTINCT sup.listener_id) FROM supporters sup " +
            "WHERE sup.artist_id = ? AND sup.listener_id IN (" +
            "  SELECT sp.user_id FROM song_plays sp WHERE sp.song_id = ? AND sp.user_id IS NOT NULL");
        params.add(artistId);
        params.add(songId);
        if (scoped) {
            sql.append(" AND sp.played_at >= ? AND sp.played_at < ?");
            params.add(start);
            params.add(end);
        }
        sql.append(")");
        sql.append(cohort(f, "sup.listener_id", params));
        return count(sql.toString(), params.toArray());
    }


    // =======================================================================
    // ★ item 6: demographics — top jurisdictions + territory drill-down.
    // Geography basis: plays/listeners use song_plays.listener_jurisdiction_id
    // (where the play happened); likes/followers/supporters carry no location
    // on the event, so they use the member's HOME jurisdiction.
    // =======================================================================

    private static final List<String> VALID_METRICS =
        List.of("plays", "listeners", "likes", "followers", "supporters");

    private LocalDateTime[] windowFor(String p, LocalDateTime now) {
        switch (p) {
            case "today":   return new LocalDateTime[]{ LocalDate.now().atStartOfDay(), null };
            case "week":    return new LocalDateTime[]{ now.minusDays(7), null };
            case "month":   return new LocalDateTime[]{ now.minusDays(30), null };
            case "quarter": return new LocalDateTime[]{ now.minusDays(90), null };
            case "year":    return new LocalDateTime[]{ now.minusDays(365), null };
            default:        return new LocalDateTime[]{ null, null };
        }
    }

    public Map<String, Object> getTopJurisdictions(UUID artistId, String period, String metric) {
        String p = (period == null || !VALID_PERIODS.contains(period)) ? "all" : period;
        String m = (metric == null || !VALID_METRICS.contains(metric)) ? "plays" : metric;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = windowFor(p, now)[0];
        boolean scoped = start != null;

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();

        switch (m) {
            case "listeners":
                sql.append(
                    "SELECT j.jurisdiction_id AS id, j.name, COUNT(DISTINCT sp.user_id) AS count " +
                    "FROM song_plays sp " +
                    "JOIN songs s ON s.song_id = sp.song_id " +
                    "JOIN jurisdictions j ON j.jurisdiction_id = sp.listener_jurisdiction_id " +
                    "WHERE s.artist_id = ? AND sp.user_id IS NOT NULL");
                params.add(artistId);
                if (scoped) {
                    sql.append(" AND sp.played_at >= ? AND sp.played_at < ?");
                    params.add(start); params.add(now);
                }
                break;
            case "likes":
                sql.append(
                    "SELECT j.jurisdiction_id AS id, j.name, COUNT(*) AS count " +
                    "FROM likes l " +
                    "JOIN songs s ON s.song_id = l.media_id " +
                    "JOIN users u ON u.user_id = l.user_id " +
                    "JOIN jurisdictions j ON j.jurisdiction_id = u.jurisdiction_id " +
                    "WHERE s.artist_id = ?");
                params.add(artistId);
                if (scoped) {
                    sql.append(" AND l.created_at >= ? AND l.created_at < ?");
                    params.add(start); params.add(now);
                }
                break;
            case "followers":
                sql.append(
                    "SELECT j.jurisdiction_id AS id, j.name, COUNT(*) AS count " +
                    "FROM follows f " +
                    "JOIN users u ON u.user_id = f.follower_id " +
                    "JOIN jurisdictions j ON j.jurisdiction_id = u.jurisdiction_id " +
                    "WHERE f.followed_id = ?");
                params.add(artistId);
                if (scoped) {
                    sql.append(" AND f.created_at IS NOT NULL AND f.created_at >= ? AND f.created_at < ?");
                    params.add(start); params.add(now);
                }
                break;
            case "supporters":
                sql.append(
                    "SELECT j.jurisdiction_id AS id, j.name, COUNT(*) AS count " +
                    "FROM supporters sup " +
                    "JOIN users u ON u.user_id = sup.listener_id " +
                    "JOIN jurisdictions j ON j.jurisdiction_id = u.jurisdiction_id " +
                    "WHERE sup.artist_id = ?");
                params.add(artistId);
                if (scoped) {
                    sql.append(" AND sup.created_at IS NOT NULL AND sup.created_at >= ? AND sup.created_at < ?");
                    params.add(start); params.add(now);
                }
                break;
            case "plays":
            default:
                sql.append(
                    "SELECT j.jurisdiction_id AS id, j.name, COUNT(*) AS count " +
                    "FROM song_plays sp " +
                    "JOIN songs s ON s.song_id = sp.song_id " +
                    "JOIN jurisdictions j ON j.jurisdiction_id = sp.listener_jurisdiction_id " +
                    "WHERE s.artist_id = ?");
                params.add(artistId);
                if (scoped) {
                    sql.append(" AND sp.played_at >= ? AND sp.played_at < ?");
                    params.add(start); params.add(now);
                }
                break;
        }

        sql.append(" GROUP BY j.jurisdiction_id, j.name ORDER BY count DESC LIMIT 12");
        List<Map<String, Object>> slices = jdbc.queryForList(sql.toString(), params.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", p);
        result.put("metric", m);
        result.put("slices", slices);
        return result;
    }

    public Map<String, Object> getTerritory(UUID artistId, UUID jurisdictionId, String period) {
        String p = (period == null || !VALID_PERIODS.contains(period)) ? "all" : period;

        Map<String, Object> jur;
        if (jurisdictionId == null) {
            List<Map<String, Object>> roots = jdbc.queryForList(
                "SELECT jurisdiction_id AS id, name, depth FROM jurisdictions " +
                "WHERE parent_jurisdiction_id IS NULL ORDER BY created_at ASC LIMIT 1");
            if (roots.isEmpty()) return null;
            jur = roots.get(0);
        } else {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT jurisdiction_id AS id, name, depth FROM jurisdictions WHERE jurisdiction_id = ?",
                jurisdictionId);
            if (rows.isEmpty()) return null;
            jur = rows.get(0);
        }
        UUID jurId = (UUID) jur.get("id");

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = windowFor(p, now)[0];
        boolean scoped = start != null;

        String subtree =
            "WITH RECURSIVE subtree AS (" +
            "SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = ? " +
            "UNION ALL " +
            "SELECT j.jurisdiction_id FROM jurisdictions j " +
            "JOIN subtree st ON j.parent_jurisdiction_id = st.jurisdiction_id) ";

        long plays = territoryCount(subtree +
            "SELECT COUNT(*) FROM song_plays sp JOIN songs s ON s.song_id = sp.song_id " +
            "WHERE s.artist_id = ? AND sp.listener_jurisdiction_id IN (SELECT jurisdiction_id FROM subtree)",
            " AND sp.played_at >= ? AND sp.played_at < ?", jurId, artistId, scoped, start, now);

        long listeners = territoryCount(subtree +
            "SELECT COUNT(DISTINCT sp.user_id) FROM song_plays sp JOIN songs s ON s.song_id = sp.song_id " +
            "WHERE s.artist_id = ? AND sp.user_id IS NOT NULL " +
            "AND sp.listener_jurisdiction_id IN (SELECT jurisdiction_id FROM subtree)",
            " AND sp.played_at >= ? AND sp.played_at < ?", jurId, artistId, scoped, start, now);

        long likes = territoryCount(subtree +
            "SELECT COUNT(*) FROM likes l JOIN songs s ON s.song_id = l.media_id " +
            "JOIN users u ON u.user_id = l.user_id " +
            "WHERE s.artist_id = ? AND u.jurisdiction_id IN (SELECT jurisdiction_id FROM subtree)",
            " AND l.created_at >= ? AND l.created_at < ?", jurId, artistId, scoped, start, now);

        long followers = territoryCount(subtree +
            "SELECT COUNT(*) FROM follows f JOIN users u ON u.user_id = f.follower_id " +
            "WHERE f.followed_id = ? AND u.jurisdiction_id IN (SELECT jurisdiction_id FROM subtree)" +
            (scoped ? " AND f.created_at IS NOT NULL" : ""),
            " AND f.created_at >= ? AND f.created_at < ?", jurId, artistId, scoped, start, now);

        long supporters = territoryCount(subtree +
            "SELECT COUNT(*) FROM supporters sup JOIN users u ON u.user_id = sup.listener_id " +
            "WHERE sup.artist_id = ? AND u.jurisdiction_id IN (SELECT jurisdiction_id FROM subtree)" +
            (scoped ? " AND sup.created_at IS NOT NULL" : ""),
            " AND sup.created_at >= ? AND sup.created_at < ?", jurId, artistId, scoped, start, now);

        List<Map<String, Object>> children = jdbc.queryForList(
            "SELECT j.jurisdiction_id AS id, j.name, " +
            "EXISTS(SELECT 1 FROM jurisdictions c WHERE c.parent_jurisdiction_id = j.jurisdiction_id) AS \"hasChildren\" " +
            "FROM jurisdictions j WHERE j.parent_jurisdiction_id = ? ORDER BY j.name ASC",
            jurId);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("plays", plays);
        stats.put("listeners", listeners);
        stats.put("likes", likes);
        stats.put("followers", followers);
        stats.put("supporters", supporters);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", p);
        result.put("jurisdiction", jur);
        result.put("stats", stats);
        result.put("children", children);
        return result;
    }

    private long territoryCount(String baseSql, String windowClause, UUID jurId, UUID artistOrTargetId,
                                boolean scoped, LocalDateTime start, LocalDateTime end) {
        if (scoped) {
            return count(baseSql + windowClause, jurId, artistOrTargetId, start, end);
        }
        return count(baseSql, jurId, artistOrTargetId);
    }
}