package com.unis.controller;

import com.unis.entity.Playlist;
import com.unis.entity.Song;
import com.unis.repository.PlaylistRepository;
import com.unis.repository.SongRepository;
import com.unis.service.AudioAnalysisService;
import com.unis.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PlaybackController -- what the web player needs for levelling, Unis Mix,
 * Liked Songs and Recently played.
 *
 *   GET    /api/v1/media/songs/analysis?ids=a,b&waveform=true   measured values (public)
 *   GET    /api/v1/playlists/{id}/mix                            a playlist's Mix switch + saved transitions (public if the playlist is)
 *   PUT    /api/v1/playlists/{id}/mix          {enabled}         owner turns Mix on/off
 *   PUT    /api/v1/playlists/{id}/mix/transition {fromSongId,toSongId,style,bars,startShift}   owner saves one transition
 *   DELETE /api/v1/playlists/{id}/mix/transition?from=&to=       owner resets one transition to Auto
 *   GET    /api/v1/me/liked-songs                                the listener's liked songs, newest first
 *   GET    /api/v1/me/recently-played?limit=50                   songs the listener played, newest first
 *   POST   /api/v1/me/recently-played/{songId}/hide              hide a song from Recently played (plays still count)
 *
 * Needs V2026_11g__playback_engine.sql.
 */
@Slf4j
@RestController
public class PlaybackController {

    private static final Set<String> STYLES = Set.of("auto", "fade", "rise", "bass", "echo", "cut", "none");
    private static final Set<Integer> BARS = Set.of(1, 2, 4, 8, 16);

    private final AudioAnalysisService analysis;
    private final JdbcTemplate jdbc;
    private final PlaylistRepository playlistRepository;
    private final SongRepository songRepository;

    public PlaybackController(AudioAnalysisService analysis, JdbcTemplate jdbc,
                              PlaylistRepository playlistRepository, SongRepository songRepository) {
        this.analysis = analysis;
        this.jdbc = jdbc;
        this.playlistRepository = playlistRepository;
        this.songRepository = songRepository;
    }

    private static UUID viewerOrNull() {
        try { return SecurityUtils.getAuthenticatedUserId(); } catch (Exception e) { return null; }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    // ------------------------------------------------------------------ analysis

    @GetMapping("/api/v1/media/songs/analysis")
    public ResponseEntity<?> analysis(@RequestParam String ids,
                                      @RequestParam(defaultValue = "true") boolean waveform) {
        List<UUID> list = new ArrayList<>();
        for (String s : ids.split(",")) {
            try { list.add(UUID.fromString(s.trim())); } catch (Exception ignored) { }
        }
        if (list.isEmpty()) return ResponseEntity.ok(List.of());
        try {
            return ResponseEntity.ok(analysis.find(list, waveform));
        } catch (Exception e) {
            log.error("[Playback] action=analysis status=error err={} fix=run backend/sql/V2026_11g__playback_engine.sql", e.getMessage());
            return ResponseEntity.ok(List.of());   // the player simply plays without levelling/mixing
        }
    }

    // ------------------------------------------------------------------ playlist mix

    @GetMapping("/api/v1/playlists/{playlistId}/mix")
    public ResponseEntity<?> getMix(@PathVariable UUID playlistId) {
        Playlist p = playlistRepository.findById(playlistId).orElse(null);
        UUID viewer = viewerOrNull();
        if (p == null || p.getDeletedAt() != null || !p.isVisibleTo(viewer)) return error(HttpStatus.NOT_FOUND, "Playlist not found");
        try {
            Boolean enabled = jdbc.queryForObject("SELECT mix_enabled FROM playlist WHERE playlist_id = ?", Boolean.class, playlistId);
            List<Map<String, Object>> transitions = jdbc.query("""
                SELECT from_song_id, to_song_id, style, bars, start_shift, updated_at
                  FROM playlist_transitions WHERE playlist_id = ?
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("fromSongId", rs.getObject("from_song_id"));
                    m.put("toSongId", rs.getObject("to_song_id"));
                    m.put("style", rs.getString("style"));
                    m.put("bars", rs.getInt("bars"));
                    m.put("startShift", rs.getInt("start_shift"));
                    m.put("updatedAt", rs.getTimestamp("updated_at"));
                    return m;
                }, playlistId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("playlistId", playlistId);
            out.put("enabled", Boolean.TRUE.equals(enabled));
            out.put("canEdit", viewer != null && p.isOwner(viewer));
            out.put("transitions", transitions);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.error("[Playback] action=mix_read status=error playlist={} err={}", playlistId, e.getMessage());
            return ResponseEntity.ok(Map.of("playlistId", playlistId, "enabled", false, "canEdit", false, "transitions", List.of()));
        }
    }

    @PutMapping("/api/v1/playlists/{playlistId}/mix")
    public ResponseEntity<?> setMix(@PathVariable UUID playlistId, @RequestBody Map<String, Object> body) {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        Playlist p = playlistRepository.findById(playlistId).orElse(null);
        if (p == null || p.getDeletedAt() != null) return error(HttpStatus.NOT_FOUND, "Playlist not found");
        if (!p.isOwner(me)) return error(HttpStatus.FORBIDDEN, "Only the playlist's owner can change its mix.");
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        jdbc.update("UPDATE playlist SET mix_enabled = ? WHERE playlist_id = ?", enabled, playlistId);
        log.info("[Playback] action=mix_toggle playlist={} enabled={}", playlistId, enabled);
        return getMix(playlistId);
    }

    @PutMapping("/api/v1/playlists/{playlistId}/mix/transition")
    public ResponseEntity<?> saveTransition(@PathVariable UUID playlistId, @RequestBody Map<String, Object> body) {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        Playlist p = playlistRepository.findById(playlistId).orElse(null);
        if (p == null || p.getDeletedAt() != null) return error(HttpStatus.NOT_FOUND, "Playlist not found");
        if (!p.isOwner(me)) return error(HttpStatus.FORBIDDEN, "Only the playlist's owner can edit its transitions.");
        UUID from, to;
        try {
            from = UUID.fromString(String.valueOf(body.get("fromSongId")));
            to = UUID.fromString(String.valueOf(body.get("toSongId")));
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "Pick the two songs this transition goes between.");
        }
        String style = String.valueOf(body.getOrDefault("style", "auto"));
        int bars = body.get("bars") instanceof Number n ? n.intValue() : 4;
        int shift = body.get("startShift") instanceof Number n ? n.intValue() : 0;
        if (!STYLES.contains(style)) return error(HttpStatus.BAD_REQUEST, "Unknown transition style.");
        if (!BARS.contains(bars)) return error(HttpStatus.BAD_REQUEST, "Length must be 1, 2, 4, 8 or 16 bars.");
        if (shift < 0 || shift > 32) return error(HttpStatus.BAD_REQUEST, "Start must be within the outro.");
        jdbc.update("""
            INSERT INTO playlist_transitions (playlist_id, from_song_id, to_song_id, style, bars, start_shift, updated_by, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT (playlist_id, from_song_id, to_song_id)
            DO UPDATE SET style = EXCLUDED.style, bars = EXCLUDED.bars, start_shift = EXCLUDED.start_shift,
                          updated_by = EXCLUDED.updated_by, updated_at = NOW()
            """, playlistId, from, to, style, bars, shift, me);
        // Saving an edit turns the playlist's Mix on: that's what the edit is for.
        jdbc.update("UPDATE playlist SET mix_enabled = TRUE WHERE playlist_id = ? AND mix_enabled = FALSE", playlistId);
        log.info("[Playback] action=transition_save playlist={} style={} bars={} shift={}", playlistId, style, bars, shift);
        return getMix(playlistId);
    }

    @DeleteMapping("/api/v1/playlists/{playlistId}/mix/transition")
    public ResponseEntity<?> resetTransition(@PathVariable UUID playlistId, @RequestParam UUID from, @RequestParam UUID to) {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        Playlist p = playlistRepository.findById(playlistId).orElse(null);
        if (p == null || p.getDeletedAt() != null) return error(HttpStatus.NOT_FOUND, "Playlist not found");
        if (!p.isOwner(me)) return error(HttpStatus.FORBIDDEN, "Only the playlist's owner can edit its transitions.");
        jdbc.update("DELETE FROM playlist_transitions WHERE playlist_id = ? AND from_song_id = ? AND to_song_id = ?", playlistId, from, to);
        return getMix(playlistId);
    }

    // ------------------------------------------------------------------ library

    @GetMapping("/api/v1/me/liked-songs")
    public ResponseEntity<?> likedSongs() {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT l.media_id AS song_id, l.created_at AS at
              FROM likes l
              JOIN songs s ON s.song_id = l.media_id AND s.deleted_at IS NULL AND s.file_url IS NOT NULL
             WHERE l.user_id = ? AND l.media_type = 'song'
             ORDER BY l.created_at DESC
             LIMIT 2000
            """, me);
        return ResponseEntity.ok(withSongs(rows, "likedAt"));
    }

    @GetMapping("/api/v1/me/recently-played")
    public ResponseEntity<?> recentlyPlayed(@RequestParam(defaultValue = "50") int limit) {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        int n = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                SELECT sp.song_id, MAX(sp.played_at) AS at
                  FROM song_plays sp
                  JOIN songs s ON s.song_id = sp.song_id AND s.deleted_at IS NULL AND s.file_url IS NOT NULL
                  LEFT JOIN recent_play_hides h ON h.user_id = sp.user_id AND h.song_id = sp.song_id
                 WHERE sp.user_id = ? AND (h.hidden_at IS NULL OR sp.played_at > h.hidden_at)
                 GROUP BY sp.song_id
                 ORDER BY at DESC
                 LIMIT ?
                """, me, n);
        } catch (Exception e) {
            log.error("[Playback] action=recents status=error err={} fix=run backend/sql/V2026_11g__playback_engine.sql", e.getMessage());
            rows = jdbc.queryForList("""
                SELECT sp.song_id, MAX(sp.played_at) AS at
                  FROM song_plays sp
                  JOIN songs s ON s.song_id = sp.song_id AND s.deleted_at IS NULL AND s.file_url IS NOT NULL
                 WHERE sp.user_id = ?
                 GROUP BY sp.song_id
                 ORDER BY at DESC
                 LIMIT ?
                """, me, n);
        }
        return ResponseEntity.ok(withSongs(rows, "playedAt"));
    }

    @PostMapping("/api/v1/me/recently-played/{songId}/hide")
    public ResponseEntity<?> hideRecent(@PathVariable UUID songId) {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        jdbc.update("""
            INSERT INTO recent_play_hides (user_id, song_id, hidden_at) VALUES (?, ?, NOW())
            ON CONFLICT (user_id, song_id) DO UPDATE SET hidden_at = NOW()
            """, me, songId);
        return ResponseEntity.ok(Map.of("hidden", true));
    }

    /** [{likedAt|playedAt, song}] in the given order, skipping songs that can't be loaded. */
    private List<Map<String, Object>> withSongs(List<Map<String, Object>> rows, String stampKey) {
        List<UUID> ids = rows.stream().map(r -> (UUID) r.get("song_id")).toList();
        Map<UUID, Song> songs = songRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Song::getSongId, Function.identity(), (a, b) -> a));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Song s = songs.get((UUID) r.get("song_id"));
            if (s == null) continue;
            Object at = r.get("at");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(stampKey, at instanceof Timestamp ts ? ts.toInstant().toString() : at);
            m.put("song", s);
            out.add(m);
        }
        return out;
    }
}
