package com.unis.service;

import com.unis.entity.Mood;
import com.unis.entity.Song;
import com.unis.entity.Subgenre;
import com.unis.repository.MoodRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.SubgenreRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side of the discovery layer: the tag lists the upload form offers,
 * how many songs each subgenre holds (for Discover's chips), the browse
 * shelves behind a subgenre or mood, and the song page's recommended carousel.
 *
 * Every query skips removed songs, songs with no audio, and clean companion
 * versions (they're reached through their explicit version), so nothing here
 * shows a song twice or a song that can't play.
 */
@Service
public class DiscoveryService {

    // Native queries bind a real UUID that matches nothing in place of "none".
    // Binding SQL NULL for a UUID parameter makes PostgreSQL fail to infer its
    // type, so absent values become this instead.
    private static final UUID NONE = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private static final String PLAYABLE = """
            c.deleted_at IS NULL
            AND c.file_url IS NOT NULL
            AND c.song_id NOT IN (SELECT clean_version_id FROM songs WHERE clean_version_id IS NOT NULL)
            """;

    // Songs inside a jurisdiction or any of its children (Harlem → Uptown, Downtown).
    private static final String IN_AREA = """
            c.jurisdiction_id IN (
                WITH RECURSIVE area AS (
                    SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :area
                    UNION ALL
                    SELECT j.jurisdiction_id FROM jurisdictions j
                    INNER JOIN area a ON j.parent_jurisdiction_id = a.jurisdiction_id
                )
                SELECT jurisdiction_id FROM area
            )
            """;

    @Autowired private SubgenreRepository subgenreRepository;
    @Autowired private MoodRepository moodRepository;
    @Autowired private SongRepository songRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static UUID orNone(UUID id) {
        return id != null ? id : NONE;
    }

    // ── What the upload form offers ──────────────────────────────────────────

    public Map<String, Object> getTaxonomy() {
        List<Map<String, String>> languages = new ArrayList<>();
        MediaService.LANGUAGES.forEach((code, name) -> {
            Map<String, String> lang = new LinkedHashMap<>();
            lang.put("code", code);
            lang.put("name", name);
            languages.add(lang);
        });

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subgenres", subgenreRepository.findByActiveTrueOrderByGenreIdAscSortOrderAsc());
        body.put("moods", moodRepository.findByActiveTrueOrderBySortOrderAsc());
        body.put("languages", languages);
        return body;
    }

    // ── Discover's chips ─────────────────────────────────────────────────────

    /**
     * Subgenres holding at least {@code minSongs} playable songs (primary or
     * secondary tag), optionally inside one jurisdiction and its children.
     * Discover shows a chip only for these, so nobody opens an empty shelf.
     */
    public List<Map<String, Object>> getSubgenreCounts(UUID jurisdictionId, int minSongs) {
        String sql = """
            SELECT t.subgenre_id, COUNT(DISTINCT t.song_id) AS songs
            FROM (
                SELECT c.song_id, c.primary_subgenre_id AS subgenre_id FROM songs c
                WHERE %1$s %2$s
                UNION ALL
                SELECT c.song_id, c.secondary_subgenre_id AS subgenre_id FROM songs c
                WHERE %1$s %2$s
            ) t
            WHERE t.subgenre_id IS NOT NULL
            GROUP BY t.subgenre_id
            HAVING COUNT(DISTINCT t.song_id) >= :min
            ORDER BY songs DESC
            """.formatted(PLAYABLE, jurisdictionId != null ? "AND " + IN_AREA : "");

        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("min", Math.max(1, minSongs));
        if (jurisdictionId != null) q.setParameter("area", jurisdictionId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("subgenreId", r[0]);
            item.put("songs", ((Number) r[1]).intValue());
            out.add(item);
        }
        return out;
    }

    // ── Browse shelves ───────────────────────────────────────────────────────

    /**
     * Songs carrying a subgenre or a mood (exactly one of the two slugs).
     * Songs inside {@code jurisdictionId} (and its children) come first, then
     * everywhere else; within each, highest score first.
     */
    public Map<String, Object> browse(String subgenreSlug, String moodSlug, UUID jurisdictionId, int limit) {
        boolean bySubgenre = subgenreSlug != null && !subgenreSlug.isBlank();
        boolean byMood = moodSlug != null && !moodSlug.isBlank();
        if (bySubgenre == byMood) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pass either subgenre or mood.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        String tagClause;
        UUID tagId;
        if (bySubgenre) {
            Subgenre sg = subgenreRepository.findBySlug(subgenreSlug.trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subgenre not found"));
            body.put("subgenre", sg);
            tagId = sg.getSubgenreId();
            tagClause = "(c.primary_subgenre_id = :tag OR c.secondary_subgenre_id = :tag)";
        } else {
            Mood mood = moodRepository.findBySlug(moodSlug.trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mood not found"));
            body.put("mood", mood);
            tagId = mood.getMoodId();
            tagClause = "(c.primary_mood_id = :tag OR c.secondary_mood_id = :tag)";
        }

        String nearFirst = jurisdictionId != null
                ? "CASE WHEN " + IN_AREA + " THEN 0 ELSE 1 END,"
                : "";
        String sql = """
            SELECT c.* FROM songs c
            WHERE %s AND %s
            ORDER BY %s COALESCE(c.score, 0) DESC, c.created_at DESC
            LIMIT :limit
            """.formatted(PLAYABLE, tagClause, nearFirst);

        Query q = entityManager.createNativeQuery(sql, Song.class);
        q.setParameter("tag", tagId);
        q.setParameter("limit", Math.max(1, Math.min(limit, 100)));
        if (jurisdictionId != null) q.setParameter("area", jurisdictionId);

        @SuppressWarnings("unchecked")
        List<Song> songs = q.getResultList();
        body.put("songs", songs);
        return body;
    }

    // ── Song page: recommended carousel ──────────────────────────────────────

    /**
     * Up to {@code limit} songs to play after this one. Each slot takes the
     * best match still available, in this order, so the carousel never has a
     * gap while any other song exists:
     *   1. same primary subgenre, same jurisdiction
     *   2. same primary subgenre, anywhere
     *   3. shares any subgenre AND a mood
     *   4. same genre AND a shared mood
     *   5. same genre
     *   6. anything else
     * Ties go to the same jurisdiction, then score, then newest.
     * Slot 2 is held for another song by the same artist, when there is one.
     */
    public List<Song> recommended(UUID songId, int limit) {
        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Song not found"));
        int size = Math.max(1, Math.min(limit, 20));

        UUID artist = song.getArtist() != null ? song.getArtist().getUserId() : NONE;
        UUID genre = song.getGenre() != null ? song.getGenre().getGenreId() : NONE;
        UUID area = song.getJurisdiction() != null ? song.getJurisdiction().getJurisdictionId() : NONE;
        UUID p = orNone(song.getPrimarySubgenre() != null ? song.getPrimarySubgenre().getSubgenreId() : null);
        UUID q2 = orNone(song.getSecondarySubgenre() != null ? song.getSecondarySubgenre().getSubgenreId() : null);
        UUID m1 = orNone(song.getPrimaryMood() != null ? song.getPrimaryMood().getMoodId() : null);
        UUID m2 = orNone(song.getSecondaryMood() != null ? song.getSecondaryMood().getMoodId() : null);

        // The same artist's best other song, for the held slot.
        Query own = entityManager.createNativeQuery("""
            SELECT c.* FROM songs c
            WHERE %s AND c.artist_id = :artist AND c.song_id <> :song
            ORDER BY COALESCE(c.score, 0) DESC, c.created_at DESC
            LIMIT 1
            """.formatted(PLAYABLE), Song.class);
        own.setParameter("artist", artist);
        own.setParameter("song", songId);
        @SuppressWarnings("unchecked")
        List<Song> ownList = own.getResultList();

        int othersWanted = ownList.isEmpty() ? size : size - 1;
        Query ranked = entityManager.createNativeQuery("""
            SELECT c.* FROM songs c
            WHERE %s AND c.song_id <> :song AND c.artist_id <> :artist
            ORDER BY
              CASE
                WHEN c.primary_subgenre_id = :p AND c.jurisdiction_id = :area THEN 1
                WHEN c.primary_subgenre_id = :p THEN 2
                WHEN (c.primary_subgenre_id IN (:p, :q) OR c.secondary_subgenre_id IN (:p, :q))
                 AND (c.primary_mood_id IN (:m1, :m2) OR c.secondary_mood_id IN (:m1, :m2)) THEN 3
                WHEN c.genre_id = :genre
                 AND (c.primary_mood_id IN (:m1, :m2) OR c.secondary_mood_id IN (:m1, :m2)) THEN 4
                WHEN c.genre_id = :genre THEN 5
                ELSE 6
              END,
              CASE WHEN c.jurisdiction_id = :area THEN 0 ELSE 1 END,
              COALESCE(c.score, 0) DESC,
              c.created_at DESC
            LIMIT :limit
            """.formatted(PLAYABLE), Song.class);
        ranked.setParameter("song", songId);
        ranked.setParameter("artist", artist);
        ranked.setParameter("area", area);
        ranked.setParameter("genre", genre);
        ranked.setParameter("p", p);
        ranked.setParameter("q", q2);
        ranked.setParameter("m1", m1);
        ranked.setParameter("m2", m2);
        ranked.setParameter("limit", Math.max(othersWanted, 1));
        @SuppressWarnings("unchecked")
        List<Song> others = othersWanted > 0 ? ranked.getResultList() : new ArrayList<>();

        List<Song> out = new ArrayList<>(others);
        if (!ownList.isEmpty()) {
            out.add(Math.min(1, out.size()), ownList.get(0));
        }
        return out.size() > size ? out.subList(0, size) : out;
    }
}
