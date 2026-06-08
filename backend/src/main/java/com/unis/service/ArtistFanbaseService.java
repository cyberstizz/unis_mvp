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
        List.of("today", "week", "month", "year", "all");

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
    // ★ advanced: completion quality for one song. (UNCHANGED)
    // =======================================================================
    private Map<String, Object> songCompletion(UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped) {
        String base =
            "SELECT COUNT(*) FILTER (WHERE completed = true) AS completed_plays, " +
            "       COUNT(*) AS total_plays, " +
            "       COALESCE(AVG(percent_played), 0) AS avg_percent " +
            "FROM song_plays WHERE song_id = ?";

        Map<String, Object> row = scoped
            ? jdbc.queryForMap(base + " AND played_at >= ? AND played_at < ?", songId, start, end)
            : jdbc.queryForMap(base, songId);

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

    // ★ advanced: discovery-source breakdown for one song. (UNCHANGED)
    private List<Map<String, Object>> songSources(UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped) {
        String base =
            "SELECT COALESCE(source, 'unknown') AS source, COUNT(*) AS count " +
            "FROM song_plays WHERE song_id = ?";

        if (scoped) {
            return jdbc.queryForList(
                base + " AND played_at >= ? AND played_at < ? " +
                "GROUP BY COALESCE(source, 'unknown') ORDER BY count DESC",
                songId, start, end);
        }
        return jdbc.queryForList(
            base + " GROUP BY COALESCE(source, 'unknown') ORDER BY count DESC",
            songId);
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
    // ★ per-song funnel — scoped to ONE song. (UNCHANGED)
    // =======================================================================
    public Map<String, Object> getSongFunnel(UUID artistId, UUID songId, String period) {
        Long owns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM songs WHERE song_id = ? AND artist_id = ?",
            Long.class, songId, artistId);
        if (owns == null || owns == 0) {
            return null;
        }

        String p = (period == null || !VALID_PERIODS.contains(period)) ? "all" : period;

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

        long totalPlays = songPlaysCount(songId, currentStart, now, scoped);
        long listeners  = songListenersCount(songId, currentStart, now, scoped);
        long likers     = songLikersCount(songId, currentStart, now, scoped);
        long voters     = songVotersCount(songId, currentStart, now, scoped);
        long followers  = songFollowersCount(artistId, songId, currentStart, now, scoped);
        long supporters = songSupportersCount(artistId, songId, currentStart, now, scoped);

        Long pL = null, pLk = null, pV = null, pF = null, pS = null;
        if (scoped) {
            pL  = songListenersCount(songId, prevStart, currentStart, true);
            pLk = songLikersCount(songId, prevStart, currentStart, true);
            pV  = songVotersCount(songId, prevStart, currentStart, true);
            pF  = songFollowersCount(artistId, songId, prevStart, currentStart, true);
            pS  = songSupportersCount(artistId, songId, prevStart, currentStart, true);
        }

        List<Map<String, Object>> funnel = new ArrayList<>();
        funnel.add(stage("listeners", "Listeners", listeners, pL));
        funnel.add(stage("likers", "Likers", likers, pLk));
        funnel.add(stage("voters", "Voters", voters, pV));
        funnel.add(stage("followers", "Followers", followers, pF));
        funnel.add(stage("supporters", "Supporters", supporters, pS));

        double repeatListenRatio = listeners > 0
            ? Math.round(((double) totalPlays / listeners) * 100.0) / 100.0
            : 0.0;

        Map<String, Object> completion = songCompletion(songId, currentStart, now, scoped);
        List<Map<String, Object>> sources = songSources(songId, currentStart, now, scoped);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", p);
        result.put("songId", songId.toString());
        result.put("funnel", funnel);
        result.put("totalPlays", totalPlays);
        result.put("uniqueListeners", listeners);
        result.put("repeatListenRatio", repeatListenRatio);
        result.put("completion", completion);
        result.put("sources", sources);
        return result;
    }

    private long songPlaysCount(UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped) {
        if (scoped) {
            return count(
                "SELECT COUNT(*) FROM song_plays WHERE song_id = ? " +
                "AND played_at >= ? AND played_at < ?",
                songId, start, end);
        }
        return count("SELECT COUNT(*) FROM song_plays WHERE song_id = ?", songId);
    }

    private long songListenersCount(UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped) {
        if (scoped) {
            return count(
                "SELECT COUNT(DISTINCT user_id) FROM song_plays WHERE song_id = ? " +
                "AND user_id IS NOT NULL AND played_at >= ? AND played_at < ?",
                songId, start, end);
        }
        return count(
            "SELECT COUNT(DISTINCT user_id) FROM song_plays " +
            "WHERE song_id = ? AND user_id IS NOT NULL",
            songId);
    }

    private long songLikersCount(UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped) {
        if (scoped) {
            return count(
                "SELECT COUNT(DISTINCT user_id) FROM likes WHERE media_id = ? " +
                "AND user_id IS NOT NULL AND created_at >= ? AND created_at < ?",
                songId, start, end);
        }
        return count(
            "SELECT COUNT(DISTINCT user_id) FROM likes " +
            "WHERE media_id = ? AND user_id IS NOT NULL",
            songId);
    }

    private long songVotersCount(UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped) {
        if (scoped) {
            return count(
                "SELECT COUNT(DISTINCT user_id) FROM votes WHERE target_id = ? " +
                "AND created_at >= ? AND created_at < ?",
                songId, start, end);
        }
        return count("SELECT COUNT(DISTINCT user_id) FROM votes WHERE target_id = ?", songId);
    }

    private long songFollowersCount(UUID artistId, UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped) {
        if (scoped) {
            return count(
                "SELECT COUNT(DISTINCT f.follower_id) FROM follows f " +
                "WHERE f.followed_id = ? AND f.follower_id IN (" +
                "  SELECT sp.user_id FROM song_plays sp " +
                "  WHERE sp.song_id = ? AND sp.user_id IS NOT NULL " +
                "  AND sp.played_at >= ? AND sp.played_at < ?)",
                artistId, songId, start, end);
        }
        return count(
            "SELECT COUNT(DISTINCT f.follower_id) FROM follows f " +
            "WHERE f.followed_id = ? AND f.follower_id IN (" +
            "  SELECT sp.user_id FROM song_plays sp " +
            "  WHERE sp.song_id = ? AND sp.user_id IS NOT NULL)",
            artistId, songId);
    }

    private long songSupportersCount(UUID artistId, UUID songId, LocalDateTime start, LocalDateTime end, boolean scoped) {
        if (scoped) {
            return count(
                "SELECT COUNT(DISTINCT sup.listener_id) FROM supporters sup " +
                "WHERE sup.artist_id = ? AND sup.listener_id IN (" +
                "  SELECT sp.user_id FROM song_plays sp " +
                "  WHERE sp.song_id = ? AND sp.user_id IS NOT NULL " +
                "  AND sp.played_at >= ? AND sp.played_at < ?)",
                artistId, songId, start, end);
        }
        return count(
            "SELECT COUNT(DISTINCT sup.listener_id) FROM supporters sup " +
            "WHERE sup.artist_id = ? AND sup.listener_id IN (" +
            "  SELECT sp.user_id FROM song_plays sp " +
            "  WHERE sp.song_id = ? AND sp.user_id IS NOT NULL)",
            artistId, songId);
    }
}