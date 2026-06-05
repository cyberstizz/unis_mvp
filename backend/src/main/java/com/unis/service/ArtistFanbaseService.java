package com.unis.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
 * follows, supporters, users). No migration needed. Pre-launch returns
 * zeros / empty lists truthfully.
 *
 * ★ period: every funnel count is now scoped by a date window. The named
 * supporter list and 30-day growth sparkline remain all-time / fixed-window
 * on purpose (scoping those to "today" would blank them and look broken).
 * Each funnel count also carries the PREVIOUS equivalent period so the UI can
 * render up/down deltas.
 */
@Service
public class ArtistFanbaseService {

    private final JdbcTemplate jdbc;

    public ArtistFanbaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ★ period: supported windows. "all" keeps the original lifetime behavior.
    private static final List<String> VALID_PERIODS =
        List.of("today", "week", "month", "year", "all");

    /**
     * @param artistId the artist
     * @param period   one of today|week|month|year|all (defaults to all)
     */
    public Map<String, Object> getArtistFanbase(UUID artistId, String period) {
        String p = (period == null || !VALID_PERIODS.contains(period)) ? "all" : period;

        // ★ period: compute [currentStart, now] and [prevStart, currentStart)
        // windows. For "all", both bounds are null → no date filter, no deltas.
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
                // no bounds
                break;
        }

        boolean scoped = currentStart != null;

        // ---- Current-window funnel ----------------------------------------
        long totalPlays = playsCount(artistId, currentStart, now, scoped);
        long listeners  = listenersCount(artistId, currentStart, now, scoped);
        long likers     = likersCount(artistId, currentStart, now, scoped);
        long voters     = votersCount(artistId, currentStart, now, scoped);
        long followers  = followersCount(artistId, currentStart, now, scoped);
        long supporters = supportersCount(artistId, currentStart, now, scoped);

        // ---- Previous-window funnel (for deltas; only when scoped) ---------
        Long prevListeners = null, prevLikers = null, prevVoters = null,
             prevFollowers = null, prevSupporters = null;

        if (scoped) {
            prevListeners  = listenersCount(artistId, prevStart, currentStart, true);
            prevLikers     = likersCount(artistId, prevStart, currentStart, true);
            prevVoters     = votersCount(artistId, prevStart, currentStart, true);
            prevFollowers  = followersCount(artistId, prevStart, currentStart, true);
            prevSupporters = supportersCount(artistId, prevStart, currentStart, true);
        }

        List<Map<String, Object>> funnel = new ArrayList<>();
        funnel.add(stage("listeners", "Listeners", listeners, prevListeners));
        funnel.add(stage("likers", "Likers", likers, prevLikers));
        funnel.add(stage("voters", "Voters", voters, prevVoters));
        funnel.add(stage("followers", "Followers", followers, prevFollowers));
        funnel.add(stage("supporters", "Supporters", supporters, prevSupporters));

        double repeatListenRatio = listeners > 0
            ? Math.round(((double) totalPlays / listeners) * 100.0) / 100.0
            : 0.0;

        // ---- Recent named supporters (ALWAYS all-time) --------------------
        List<Map<String, Object>> recentSupporters = jdbc.queryForList(
            "SELECT sup.listener_id AS \"userId\", u.username, u.photo_url AS \"photoUrl\", " +
            "       sup.created_at AS \"since\" " +
            "FROM supporters sup " +
            "JOIN users u ON u.user_id = sup.listener_id " +
            "WHERE sup.artist_id = ? AND u.deleted_at IS NULL " +
            "ORDER BY sup.created_at DESC " +
            "LIMIT 12",
            artistId);

        // ---- 30-day supporter growth (ALWAYS fixed 30-day window) ---------
        List<Map<String, Object>> supporterGrowth = jdbc.queryForList(
            "SELECT created_at::date AS day, COUNT(*) AS count " +
            "FROM supporters " +
            "WHERE artist_id = ? AND created_at >= NOW() - INTERVAL '30 days' " +
            "GROUP BY created_at::date " +
            "ORDER BY day ASC",
            artistId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", p);                 // ★ echo back the resolved period
        result.put("funnel", funnel);
        result.put("totalPlays", totalPlays);
        result.put("uniqueListeners", listeners);
        result.put("repeatListenRatio", repeatListenRatio);
        result.put("recentSupporters", recentSupporters);
        result.put("supporterGrowth", supporterGrowth);
        return result;
    }

    // -----------------------------------------------------------------------
    // Per-stage counts. Each appends an optional date window. When unscoped
    // (all-time), the window clause and its params are omitted.
    // -----------------------------------------------------------------------

    private long playsCount(UUID artistId, LocalDateTime start, LocalDateTime end, boolean scoped) {
        if (scoped) {
            return count(
                "SELECT COUNT(*) FROM song_plays sp " +
                "JOIN songs s ON s.song_id = sp.song_id " +
                "WHERE s.artist_id = ? AND sp.played_at >= ? AND sp.played_at < ?",
                artistId, start, end);
        }
        return count(
            "SELECT COUNT(*) FROM song_plays sp " +
            "JOIN songs s ON s.song_id = sp.song_id " +
            "WHERE s.artist_id = ?",
            artistId);
    }

    private long listenersCount(UUID artistId, LocalDateTime start, LocalDateTime end, boolean scoped) {
        if (scoped) {
            return count(
                "SELECT COUNT(DISTINCT sp.user_id) FROM song_plays sp " +
                "JOIN songs s ON s.song_id = sp.song_id " +
                "WHERE s.artist_id = ? AND sp.user_id IS NOT NULL " +
                "AND sp.played_at >= ? AND sp.played_at < ?",
                artistId, start, end);
        }
        return count(
            "SELECT COUNT(DISTINCT sp.user_id) FROM song_plays sp " +
            "JOIN songs s ON s.song_id = sp.song_id " +
            "WHERE s.artist_id = ? AND sp.user_id IS NOT NULL",
            artistId);
    }

    private long likersCount(UUID artistId, LocalDateTime start, LocalDateTime end, boolean scoped) {
        if (scoped) {
            return count(
                "SELECT COUNT(DISTINCT l.user_id) FROM likes l " +
                "JOIN songs s ON s.song_id = l.media_id " +
                "WHERE s.artist_id = ? AND l.user_id IS NOT NULL " +
                "AND l.created_at >= ? AND l.created_at < ?",
                artistId, start, end);
        }
        return count(
            "SELECT COUNT(DISTINCT l.user_id) FROM likes l " +
            "JOIN songs s ON s.song_id = l.media_id " +
            "WHERE s.artist_id = ? AND l.user_id IS NOT NULL",
            artistId);
    }

    private long votersCount(UUID artistId, LocalDateTime start, LocalDateTime end, boolean scoped) {
        if (scoped) {
            return count(
                "SELECT COUNT(DISTINCT v.user_id) FROM votes v " +
                "WHERE (v.target_id = ? " +
                "   OR v.target_id IN (SELECT song_id FROM songs WHERE artist_id = ?)) " +
                "AND v.created_at >= ? AND v.created_at < ?",
                artistId, artistId, start, end);
        }
        return count(
            "SELECT COUNT(DISTINCT v.user_id) FROM votes v " +
            "WHERE v.target_id = ? " +
            "   OR v.target_id IN (SELECT song_id FROM songs WHERE artist_id = ?)",
            artistId, artistId);
    }

    // ★ follows.created_at is nullable. When scoped, null dates can't be
    // attributed to a window, so they're excluded; all-time keeps everything.
    private long followersCount(UUID artistId, LocalDateTime start, LocalDateTime end, boolean scoped) {
        if (scoped) {
            return count(
                "SELECT COUNT(*) FROM follows " +
                "WHERE followed_id = ? AND created_at IS NOT NULL " +
                "AND created_at >= ? AND created_at < ?",
                artistId, start, end);
        }
        return count(
            "SELECT COUNT(*) FROM follows WHERE followed_id = ?",
            artistId);
    }

    private long supportersCount(UUID artistId, LocalDateTime start, LocalDateTime end, boolean scoped) {
        if (scoped) {
            return count(
                "SELECT COUNT(*) FROM supporters " +
                "WHERE artist_id = ? AND created_at IS NOT NULL " +
                "AND created_at >= ? AND created_at < ?",
                artistId, start, end);
        }
        return count(
            "SELECT COUNT(*) FROM supporters WHERE artist_id = ?",
            artistId);
    }

    // ★ stage now carries an optional previous-period value + a signed delta.
    private Map<String, Object> stage(String key, String label, long value, Long prevValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("value", value);
        if (prevValue != null) {
            m.put("previous", prevValue);
            m.put("delta", value - prevValue);   // negative = down, positive = up
        } else {
            m.put("previous", null);
            m.put("delta", null);                // all-time: no comparison
        }
        return m;
    }

    private long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0L : v;
    }



    // =======================================================================
    // ★ per-song funnel — mirrors the artist funnel but scoped to ONE song.
    // followers/supporters are defined as "followers/supporters who have
    // played THIS song" (a follow/support carries no song_id, so we join
    // against song_plays). Period scoping identical to the artist funnel.
    // No named-supporter grid — just the funnel + repeat ratio.
    // =======================================================================
    public Map<String, Object> getSongFunnel(UUID artistId, UUID songId, String period) {
        // Ownership guard: the song must belong to the requesting artist.
        Long owns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM songs WHERE song_id = ? AND artist_id = ?",
            Long.class, songId, artistId);
        if (owns == null || owns == 0) {
            return null; // controller translates to 403/404
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", p);
        result.put("songId", songId.toString());
        result.put("funnel", funnel);
        result.put("totalPlays", totalPlays);
        result.put("uniqueListeners", listeners);
        result.put("repeatListenRatio", repeatListenRatio);
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

    // ★ "followers who have played this song." The follow must exist; a play of
    // this song must exist. Follow date is irrelevant by this definition.
    // When scoped, the PLAY is what's date-filtered (the meaningful event).
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

    // ★ "supporters who have played this song." Same shape as followers.
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