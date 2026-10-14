package com.unis.service;

import com.unis.entity.Song;
import com.unis.repository.SongRepository;
import com.unis.util.ClientPlatform;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Plays by people who aren't logged in.
 *
 * Stored in anonymous_plays, apart from song_plays on purpose: these never
 * earn points, never change a song's score or play count, never count toward
 * awards, and never make anyone a "user" in the metrics. They exist so the
 * reports can show the catalog's real reach ("logged-out listening").
 *
 * The visitor ID is a random ID the browser or app keeps; it isn't tied to a
 * person. The same rules as logged-in plays keep the numbers honest: the
 * player only reports a play after the listening threshold, one visitor
 * can't count the same song twice within 30 minutes, and a visitor stops
 * counting after 500 plays in 24 hours.
 */
@Service
public class AnonymousPlayService {

    private static final int COOLDOWN_MINUTES = 30;
    private static final int DAILY_CAP = 500;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SongRepository songRepository;

    /** Records the play and returns its ID, or null when it doesn't count. */
    public UUID record(UUID songId, UUID visitorId, String source) {
        if (songId == null || visitorId == null) return null;

        Song song = songRepository.findById(songId).orElse(null);
        if (song == null || song.isRemoved()) return null;

        LocalDateTime now = LocalDateTime.now();
        Integer recent = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM anonymous_plays WHERE visitor_id = ? AND song_id = ? AND played_at > ?",
            Integer.class, visitorId, songId, now.minusMinutes(COOLDOWN_MINUTES));
        if (recent != null && recent > 0) return null;

        Integer today = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM anonymous_plays WHERE visitor_id = ? AND played_at > ?",
            Integer.class, visitorId, now.minusHours(24));
        if (today != null && today >= DAILY_CAP) return null;

        UUID playId = UUID.randomUUID();
        int durationSecs = song.getDuration() != null ? song.getDuration() / 1000 : 180;
        String cleanSource = source == null ? null
            : source.trim().substring(0, Math.min(40, source.trim().length()));

        jdbcTemplate.update(
            "INSERT INTO anonymous_plays (play_id, visitor_id, song_id, played_at, duration_secs, source, platform) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            playId, visitorId, songId, now, durationSecs, cleanSource, ClientPlatform.current());
        return playId;
    }

    /** The player's "play finished" message. Only the visitor who started the play can finish it, once. */
    public void complete(UUID playId, UUID visitorId, double percentPlayed) {
        if (playId == null || visitorId == null) return;
        double pct = Math.max(0, Math.min(100, percentPlayed));
        jdbcTemplate.update(
            "UPDATE anonymous_plays SET percent_played = ?, completed = ? "
          + "WHERE play_id = ? AND visitor_id = ? AND percent_played IS NULL",
            BigDecimal.valueOf(pct), pct >= 80.0, playId, visitorId);
    }
}
