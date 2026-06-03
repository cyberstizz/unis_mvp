package com.unis.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Artist-facing fanbase analytics for the Artist Dashboard.
 *
 * NOTE: This is distinct from the platform/admin AnalyticsService (DAU/MAU/DMCA
 * /referrals). It was renamed from "AnalyticsService" to "ArtistFanbaseService"
 * so both can coexist as separate Spring beans in com.unis.service.
 *
 * All queries run against tables that already exist:
 *   song_plays, songs, likes, votes, follows, supporters, users.
 * No migration is needed. Pre-launch this correctly returns zeros / empty lists.
 */
@Service
public class ArtistFanbaseService {

    private final JdbcTemplate jdbc;

    public ArtistFanbaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> getArtistFanbase(UUID artistId) {

        // ---- Funnel stages -------------------------------------------------
        long totalPlays = count(
            "SELECT COUNT(*) FROM song_plays sp " +
            "JOIN songs s ON s.song_id = sp.song_id " +
            "WHERE s.artist_id = ?",
            artistId);

        long listeners = count(
            "SELECT COUNT(DISTINCT sp.user_id) FROM song_plays sp " +
            "JOIN songs s ON s.song_id = sp.song_id " +
            "WHERE s.artist_id = ? AND sp.user_id IS NOT NULL",
            artistId);

        long likers = count(
            "SELECT COUNT(DISTINCT l.user_id) FROM likes l " +
            "JOIN songs s ON s.song_id = l.media_id " +
            "WHERE s.artist_id = ? AND l.user_id IS NOT NULL",
            artistId);

        long voters = count(
            "SELECT COUNT(DISTINCT v.user_id) FROM votes v " +
            "WHERE v.target_id = ? " +
            "   OR v.target_id IN (SELECT song_id FROM songs WHERE artist_id = ?)",
            artistId, artistId);

        long followers = count(
            "SELECT COUNT(*) FROM follows WHERE followed_id = ?",
            artistId);

        long supporters = count(
            "SELECT COUNT(*) FROM supporters WHERE artist_id = ?",
            artistId);

        List<Map<String, Object>> funnel = new ArrayList<>();
        funnel.add(stage("listeners", "Listeners", listeners));
        funnel.add(stage("likers", "Likers", likers));
        funnel.add(stage("voters", "Voters", voters));
        funnel.add(stage("followers", "Followers", followers));
        funnel.add(stage("supporters", "Supporters", supporters));

        double repeatListenRatio = listeners > 0
            ? Math.round(((double) totalPlays / listeners) * 100.0) / 100.0
            : 0.0;

        // ---- Recent named supporters --------------------------------------
        List<Map<String, Object>> recentSupporters = jdbc.queryForList(
            "SELECT sup.listener_id AS \"userId\", u.username, u.photo_url AS \"photoUrl\", " +
            "       sup.created_at AS \"since\" " +
            "FROM supporters sup " +
            "JOIN users u ON u.user_id = sup.listener_id " +
            "WHERE sup.artist_id = ? AND u.deleted_at IS NULL " +
            "ORDER BY sup.created_at DESC " +
            "LIMIT 12",
            artistId);

        // ---- 30-day supporter growth --------------------------------------
        List<Map<String, Object>> supporterGrowth = jdbc.queryForList(
            "SELECT created_at::date AS day, COUNT(*) AS count " +
            "FROM supporters " +
            "WHERE artist_id = ? AND created_at >= NOW() - INTERVAL '30 days' " +
            "GROUP BY created_at::date " +
            "ORDER BY day ASC",
            artistId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("funnel", funnel);
        result.put("totalPlays", totalPlays);
        result.put("uniqueListeners", listeners);
        result.put("repeatListenRatio", repeatListenRatio);
        result.put("recentSupporters", recentSupporters);
        result.put("supporterGrowth", supporterGrowth);
        return result;
    }

    private Map<String, Object> stage(String key, String label, long value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("value", value);
        return m;
    }

    private long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0L : v;
    }
}