package com.unis.service;

import com.unis.entity.Release;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Release alerts: telling followers when an artist they follow puts out music.
 *
 * <p><b>Writing.</b> One row per artist per day (New York time). Publishing a
 * release or uploading a song adds to that day's row, so a busy day is still
 * one alert. Nothing is written per follower: who sees an alert is worked out
 * when they look, from the follows table.
 *
 * <p><b>Who sees it.</b> Followers whose bell for that artist is ALL (every
 * release, the default), or MAJOR when the alert includes an album, EP or
 * mixtape. OFF hides the artist. Only alerts from after they followed, from
 * the last 90 days.
 *
 * <p><b>Email.</b> ReleaseAlertEmailScheduler sends at most one email a day to
 * followers with email on, covering alerts they haven't already seen in Unis.
 */
@Service
@Slf4j
public class ReleaseAlertService {

    public static final ZoneId ZONE = ZoneId.of("America/New_York");
    public static final int KEEP_DAYS = 90;
    public static final int NOTE_MAX = 280;
    private static final int LIST_LIMIT = 500;

    public static final Set<String> LEVELS = Set.of("ALL", "MAJOR", "OFF");

    /** One alert as a follower sees it. kind is RELEASE or SONG. */
    public record Item(UUID alertId, UUID artistId, String artistName, String artistPhotoUrl,
                       String kind, UUID releaseId, String releaseType, UUID songId,
                       String title, String coverUrl, int songCount, String note,
                       LocalDateTime at, boolean unseen) {}

    /** names: who the row talks about (artists with unseen alerts, or everyone when nothing is new). */
    public record Summary(int unseen, LocalDateTime seenAt, boolean inApp, boolean email, List<Item> preview, List<String> names) {}

    public record Settings(boolean inApp, boolean email) {}

    public record FollowedArtist(UUID artistId, String name, String photoUrl, String level) {}

    /** The artist's latest alert, for the dashboard. */
    public record Reach(UUID alertId, String kind, UUID releaseId, String releaseType, UUID songId, String title,
                        String coverUrl, int reach, long newFollowersSince, LocalDateTime at) {}

    /** One follower's email: who, and what's new for them. */
    public record EmailRecipient(UUID userId, String username, String email, UUID unsubscribeToken, boolean artist) {}

    private final JdbcTemplate jdbc;

    public ReleaseAlertService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    /**
     * A release went out. Called after the release is committed; runs in its
     * own transaction so a problem here can never undo the release.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releasePublished(UUID artistId, UUID releaseId, Release.Type type, Collection<UUID> trackIds, String note) {
        LocalDate day = today();
        LocalDateTime now = LocalDateTime.now();
        boolean major = type != null && type != Release.Type.SINGLE;
        String cleanNote = cleanNote(note);

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT alert_id, song_id, song_count, major, note FROM release_alerts WHERE artist_id = ? AND alert_day = ? FOR UPDATE",
            artistId, day);
        if (rows.isEmpty()) {
            jdbc.update("""
                INSERT INTO release_alerts (artist_id, alert_day, release_id, song_id, song_count, major, note, created_at, updated_at)
                VALUES (?, ?, ?, NULL, 0, ?, ?, ?, ?)
                ON CONFLICT (artist_id, alert_day) DO UPDATE
                   SET release_id = EXCLUDED.release_id, major = release_alerts.major OR EXCLUDED.major,
                       note = COALESCE(EXCLUDED.note, release_alerts.note), updated_at = EXCLUDED.updated_at
                """, artistId, day, releaseId, major, cleanNote, ts(now), ts(now));
        } else {
            Map<String, Object> row = rows.get(0);
            UUID songId = (UUID) row.get("song_id");
            int count = ((Number) row.get("song_count")).intValue();
            if (songId != null && trackIds != null && trackIds.contains(songId)) {
                // Today's song is on the release: the release is the news now.
                songId = null;
                count = Math.max(0, count - 1);
            }
            boolean wasMajor = Boolean.TRUE.equals(row.get("major"));
            String keptNote = cleanNote != null ? cleanNote : (String) row.get("note");
            jdbc.update("""
                UPDATE release_alerts SET release_id = ?, song_id = ?, song_count = ?, major = ?, note = ?, updated_at = ?
                WHERE alert_id = ?
                """, releaseId, songId, count, wasMajor || major, keptNote, ts(now), row.get("alert_id"));
        }
        int reach = refreshReach(artistId, day);
        log.info("[ReleaseAlert] release artist={} release={} major={} reach={}", artistId, releaseId, major, reach);
    }

    /** A song was uploaded (not a clean companion version). Same rules as above. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void songUploaded(UUID artistId, UUID songId) {
        LocalDate day = today();
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT alert_id, song_id FROM release_alerts WHERE artist_id = ? AND alert_day = ? FOR UPDATE", artistId, day);
        if (rows.isEmpty()) {
            jdbc.update("""
                INSERT INTO release_alerts (artist_id, alert_day, song_id, song_count, major, created_at, updated_at)
                VALUES (?, ?, ?, 1, FALSE, ?, ?)
                ON CONFLICT (artist_id, alert_day) DO UPDATE
                   SET song_id = COALESCE(release_alerts.song_id, EXCLUDED.song_id),
                       song_count = release_alerts.song_count + 1, updated_at = EXCLUDED.updated_at
                """, artistId, day, songId, ts(now), ts(now));
        } else {
            Map<String, Object> row = rows.get(0);
            UUID kept = row.get("song_id") != null ? (UUID) row.get("song_id") : songId;
            jdbc.update("UPDATE release_alerts SET song_id = ?, song_count = song_count + 1, updated_at = ? WHERE alert_id = ?",
                kept, ts(now), row.get("alert_id"));
        }
        int reach = refreshReach(artistId, day);
        log.info("[ReleaseAlert] song artist={} song={} reach={}", artistId, songId, reach);
    }

    /** How many followers this alert reaches, given their bells. */
    private int refreshReach(UUID artistId, LocalDate day) {
        Integer n = jdbc.queryForObject("""
            SELECT COUNT(*) FROM follows f
            JOIN users u ON u.user_id = f.follower_id AND u.deleted_at IS NULL
            JOIN release_alerts a ON a.artist_id = f.followed_id AND a.alert_day = ?
            WHERE f.followed_id = ?
              AND f.alert_level <> 'OFF'
              AND (f.alert_level = 'ALL' OR a.major)
            """, Integer.class, day, artistId);
        int reach = n == null ? 0 : n;
        jdbc.update("UPDATE release_alerts SET reach = ? WHERE artist_id = ? AND alert_day = ?", reach, artistId, day);
        return reach;
    }

    public static String cleanNote(String note) {
        if (note == null) return null;
        String n = note.trim();
        if (n.isEmpty()) return null;
        if (n.length() > NOTE_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "The note to your followers can be up to " + NOTE_MAX + " characters.");
        }
        return n;
    }

    // ── Reading (followers) ─────────────────────────────────────────────────

    /** Shared by the list and the email: alerts from followed artists, with what they point to. */
    private static final String ITEM_COLUMNS = """
        a.alert_id, a.artist_id, ar.username AS artist_name, ar.photo_url AS artist_photo,
        r.release_id, r.release_type, r.title AS release_title,
        COALESCE(r.cover_url, (SELECT s2.artwork_url FROM songs s2
                                WHERE s2.release_id = r.release_id AND s2.deleted_at IS NULL
                                ORDER BY s2.track_number NULLS LAST LIMIT 1)) AS release_cover,
        s.song_id, s.title AS song_title, s.artwork_url AS song_cover,
        a.song_count, a.note, a.updated_at
        """;

    private static final String ITEM_JOINS = """
        FROM release_alerts a
        JOIN follows f ON f.followed_id = a.artist_id
        JOIN users ar ON ar.user_id = a.artist_id AND ar.deleted_at IS NULL
        LEFT JOIN releases r ON r.release_id = a.release_id AND r.deleted_at IS NULL
        LEFT JOIN songs s ON s.song_id = a.song_id AND s.deleted_at IS NULL
        """;

    private static final String ITEM_FILTER = """
          AND f.alert_level <> 'OFF'
          AND (f.alert_level = 'ALL' OR a.major)
          AND (f.created_at IS NULL OR a.updated_at >= f.created_at)
          AND (r.release_id IS NOT NULL OR s.song_id IS NOT NULL)
        """;

    @Transactional(readOnly = true)
    public List<Item> list(UUID me) {
        LocalDateTime seenAt = seenAt(me);
        LocalDateTime since = LocalDateTime.now().minusDays(KEEP_DAYS);
        return jdbc.query("SELECT " + ITEM_COLUMNS + ITEM_JOINS
                + " WHERE f.follower_id = ? AND a.updated_at >= ?" + ITEM_FILTER
                + " ORDER BY a.updated_at DESC LIMIT " + LIST_LIMIT,
            (rs, i) -> item(rs, seenAt), me, ts(since));
    }

    @Transactional(readOnly = true)
    public Summary summary(UUID me) {
        Settings s = settings(me);
        LocalDateTime seenAt = seenAt(me);
        if (!s.inApp()) return new Summary(0, seenAt, false, s.email(), List.of(), List.of());
        List<Item> items = list(me);
        int unseen = (int) items.stream().filter(Item::unseen).count();
        List<String> names = items.stream()
            .filter(i -> unseen == 0 || i.unseen())
            .map(Item::artistName)
            .distinct()
            .limit(20)
            .toList();
        return new Summary(unseen, seenAt, true, s.email(), new ArrayList<>(items.subList(0, Math.min(3, items.size()))), names);
    }

    @Transactional
    public void markSeen(UUID me) {
        jdbc.update("UPDATE users SET release_alerts_seen_at = ? WHERE user_id = ?", ts(LocalDateTime.now()), me);
    }

    @Transactional(readOnly = true)
    public Settings settings(UUID me) {
        List<Settings> found = jdbc.query(
            "SELECT release_alerts_in_app, release_alerts_email FROM users WHERE user_id = ?",
            (rs, i) -> new Settings(rs.getBoolean(1), rs.getBoolean(2)), me);
        return found.isEmpty() ? new Settings(true, true) : found.get(0);
    }

    @Transactional
    public Settings updateSettings(UUID me, Boolean inApp, Boolean email) {
        if (inApp != null) jdbc.update("UPDATE users SET release_alerts_in_app = ? WHERE user_id = ?", inApp, me);
        if (email != null) jdbc.update("UPDATE users SET release_alerts_email = ? WHERE user_id = ?", email, me);
        return settings(me);
    }

    /** The bell for one artist, or null when not following them. */
    @Transactional(readOnly = true)
    public String level(UUID me, UUID artistId) {
        List<String> found = jdbc.queryForList(
            "SELECT alert_level FROM follows WHERE follower_id = ? AND followed_id = ?", String.class, me, artistId);
        return found.isEmpty() ? null : found.get(0);
    }

    @Transactional
    public String setLevel(UUID me, UUID artistId, String level) {
        String l = level == null ? "" : level.trim().toUpperCase();
        if (!LEVELS.contains(l)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose every release, big releases only, or off.");
        }
        int n = jdbc.update("UPDATE follows SET alert_level = ? WHERE follower_id = ? AND followed_id = ?", l, me, artistId);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Follow this artist first.");
        return l;
    }

    @Transactional(readOnly = true)
    public List<FollowedArtist> followed(UUID me) {
        return jdbc.query("""
            SELECT u.user_id, u.username, u.photo_url, f.alert_level
            FROM follows f JOIN users u ON u.user_id = f.followed_id
            WHERE f.follower_id = ? AND u.deleted_at IS NULL AND u.role::text = 'artist'
            ORDER BY lower(u.username)
            """, (rs, i) -> new FollowedArtist(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)), me);
    }

    // ── Reading (the artist) ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Reach latestForArtist(UUID artistId) {
        List<Reach> found = jdbc.query("""
            SELECT a.alert_id, a.reach, a.created_at, a.updated_at,
                   r.release_id, r.release_type, r.title AS release_title,
                   COALESCE(r.cover_url, (SELECT s2.artwork_url FROM songs s2
                                           WHERE s2.release_id = r.release_id AND s2.deleted_at IS NULL
                                           ORDER BY s2.track_number NULLS LAST LIMIT 1)) AS release_cover,
                   s.song_id, s.title AS song_title, s.artwork_url AS song_cover,
                   (SELECT COUNT(*) FROM follows fo WHERE fo.followed_id = a.artist_id AND fo.created_at >= a.created_at) AS new_followers
            FROM release_alerts a
            LEFT JOIN releases r ON r.release_id = a.release_id AND r.deleted_at IS NULL
            LEFT JOIN songs s ON s.song_id = a.song_id AND s.deleted_at IS NULL
            WHERE a.artist_id = ? AND (r.release_id IS NOT NULL OR s.song_id IS NOT NULL)
            ORDER BY a.updated_at DESC LIMIT 1
            """, (rs, i) -> {
                boolean rel = rs.getObject("release_id") != null;
                return new Reach(rs.getObject("alert_id", UUID.class), rel ? "RELEASE" : "SONG",
                    rs.getObject("release_id", UUID.class), rs.getString("release_type"),
                    rs.getObject("song_id", UUID.class),
                    rel ? rs.getString("release_title") : rs.getString("song_title"),
                    rel ? rs.getString("release_cover") : rs.getString("song_cover"),
                    rs.getInt("reach"), rs.getLong("new_followers"), time(rs, "updated_at"));
            }, artistId);
        return found.isEmpty() ? null : found.get(0);
    }

    // ── Email ───────────────────────────────────────────────────────────────

    /**
     * Everyone with something new to hear about by email, with their items.
     * New = changed since the later of their last email and {@code floor},
     * and not already seen in Unis.
     */
    @Transactional(readOnly = true)
    public Map<EmailRecipient, List<Item>> emailBatch(LocalDateTime floor) {
        Map<UUID, EmailRecipient> people = new LinkedHashMap<>();
        Map<EmailRecipient, List<Item>> out = new LinkedHashMap<>();
        jdbc.query("SELECT u.user_id AS reader_id, u.username AS reader_name, u.email AS reader_email, u.unsubscribe_token, u.role::text AS reader_role, "
                + ITEM_COLUMNS + ITEM_JOINS
                + " JOIN users u ON u.user_id = f.follower_id"
                + " WHERE u.deleted_at IS NULL AND u.email_notifications = TRUE AND u.release_alerts_email = TRUE"
                + "   AND u.email_verified = TRUE AND u.email IS NOT NULL AND u.email <> ''"
                + "   AND a.updated_at > GREATEST(COALESCE(u.release_alerts_emailed_at, ?), ?)"
                + "   AND (u.release_alerts_seen_at IS NULL OR a.updated_at > u.release_alerts_seen_at)"
                + ITEM_FILTER
                + " ORDER BY u.user_id, a.updated_at DESC",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                UUID id = rs.getObject("reader_id", UUID.class);
                EmailRecipient r = people.computeIfAbsent(id, k -> {
                    try {
                        return new EmailRecipient(id, rs.getString("reader_name"), rs.getString("reader_email"),
                            rs.getObject("unsubscribe_token", UUID.class), "artist".equalsIgnoreCase(rs.getString("reader_role")));
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
                out.computeIfAbsent(r, k -> new ArrayList<>()).add(item(rs, null));
            }, ts(floor), ts(floor));
        return out;
    }

    @Transactional
    public void markEmailed(UUID userId, LocalDateTime at) {
        jdbc.update("UPDATE users SET release_alerts_emailed_at = ? WHERE user_id = ?", ts(at), userId);
    }

    /** One-click unsubscribe from release emails only. */
    @Transactional
    public boolean unsubscribeByToken(UUID token) {
        if (token == null) return false;
        return jdbc.update("UPDATE users SET release_alerts_email = FALSE WHERE unsubscribe_token = ? AND deleted_at IS NULL",
            token) > 0;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private LocalDateTime seenAt(UUID me) {
        List<LocalDateTime> found = jdbc.query("SELECT release_alerts_seen_at FROM users WHERE user_id = ?",
            (rs, i) -> time(rs, 1), me);
        return found.isEmpty() ? null : found.get(0);
    }

    private static Item item(ResultSet rs, LocalDateTime seenAt) throws SQLException {
        boolean rel = rs.getObject("release_id") != null;
        LocalDateTime at = time(rs, "updated_at");
        return new Item(
            rs.getObject("alert_id", UUID.class),
            rs.getObject("artist_id", UUID.class),
            rs.getString("artist_name"),
            rs.getString("artist_photo"),
            rel ? "RELEASE" : "SONG",
            rs.getObject("release_id", UUID.class),
            rs.getString("release_type"),
            rs.getObject("song_id", UUID.class),
            rel ? rs.getString("release_title") : rs.getString("song_title"),
            rel ? rs.getString("release_cover") : rs.getString("song_cover"),
            rs.getInt("song_count"),
            rs.getString("note"),
            at,
            seenAt == null || (at != null && at.isAfter(seenAt)));
    }

    private static LocalDateTime time(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    private static LocalDateTime time(ResultSet rs, int col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    private static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }
}
