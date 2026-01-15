package com.unis.service;

import com.fasterxml.jackson.databind.ObjectMapper; 
import com.unis.dto.SongUploadRequest;
import com.unis.dto.VideoUploadRequest;
import com.unis.entity.Song;
import com.unis.entity.Video;
import com.unis.entity.SongPlay;
import com.unis.entity.VideoPlay;
import com.unis.entity.User;
import com.unis.entity.Genre;
import com.unis.entity.Jurisdiction;
import com.unis.repository.SongRepository;
import com.unis.repository.VideoRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import com.unis.repository.SongPlayRepository;
import com.unis.repository.VideoPlayRepository;
import com.unis.repository.UserRepository;
import com.unis.repository.GenreRepository;
import com.unis.repository.JurisdictionRepository;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;


@Service
@Transactional
public class MediaService {

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private SongPlayRepository songPlayRepository;

    @Autowired
    private VideoPlayRepository videoPlayRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private JurisdictionRepository jurisdictionRepository;

    @Autowired 
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScoreUpdateService scoreUpdateService;

    @Autowired
    private FileStorageService fileStorageService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Add song - EVICTS cache because new content affects trending/artist lists
    @CacheEvict(value = {"songs", "artists", "trending"}, allEntries = true)
    public Song addSong(String songJson, MultipartFile file, MultipartFile artwork) {
        try {
            SongUploadRequest req = objectMapper.readValue(songJson, SongUploadRequest.class);
            
            // Guards 
            if (req.getTitle() == null || req.getTitle().trim().isEmpty()) {
                throw new IllegalArgumentException("Title is required");
            }
            if (req.getArtistId() == null) {
                throw new IllegalArgumentException("Artist ID is required and cannot be null");
            }
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("Audio file is required");
            }

            // Resolve artist
            User artist = userRepository.findById(req.getArtistId())
                    .orElseThrow(() -> new IllegalArgumentException("Artist not found: " + req.getArtistId()));

            // Resolve genre (optional)
            Genre genre = null;
            if (req.getGenreId() != null) {
                genre = genreRepository.findById(req.getGenreId()).orElse(null);
            }

            // Resolve jurisdiction
            Jurisdiction jurisdiction = null;
            if (req.getJurisdictionId() != null) {
                jurisdiction = jurisdictionRepository.findById(req.getJurisdictionId())
                        .orElseThrow(() -> new IllegalArgumentException("Jurisdiction not found: " + req.getJurisdictionId()));
            } else if (artist.getJurisdiction() != null) {
                jurisdiction = artist.getJurisdiction();
            } else {
                jurisdiction = jurisdictionRepository.findById(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                        .orElseThrow(() -> new IllegalArgumentException("Default jurisdiction not found"));
            }

            // File storage
            String fileUrl = fileStorageService.storeFile(file);
            String artworkUrl = null;
            if (artwork != null && !artwork.isEmpty()) {
                artworkUrl = fileStorageService.storeFile(artwork);
            }

            // Duration 
            Integer duration = req.getDuration() != null ? req.getDuration() : computeDuration(file);

            // Build & save 
            Song song = new Song();
            song.setTitle(req.getTitle());
            song.setArtist(artist);
            song.setGenre(genre);
            song.setJurisdiction(jurisdiction);
            song.setDescription(req.getDescription());
            song.setDuration(duration);
            song.setFileUrl(fileUrl);
            song.setArtworkUrl(artworkUrl);
            song.setScore(0);
            song.setLevel("silver");
            song.setCreatedAt(LocalDateTime.now());
            song.setExplicit(req.getExplicit() != null ? req.getExplicit() : false);
            song.setLyrics(req.getLyrics());
            song.setPlaysToday(0);
            song.setLastPlayResetDate(LocalDate.now());

            Song savedSong = songRepository.save(song);

            // If this is the artist's first song (no default song set), make it the default
            if (artist.getDefaultSongId() == null) {
                artist.setDefaultSongId(savedSong.getSongId());
                userRepository.save(artist);
            }

            return savedSong;
        } catch (IOException e) {
            throw new RuntimeException("JSON parse or file upload failed", e);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid upload data: " + e.getMessage(), e);
        }
    }

    // NOT CACHED - private helper method
    private Integer computeDuration(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            System.err.println("File is null or empty, returning fallback duration");
            return 180000;
        }
        
        try (InputStream is = file.getInputStream()) {
            Metadata metadata = new Metadata();
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1);
            ParseContext context = new ParseContext();
            
            parser.parse(is, handler, metadata, context);
            
            System.out.println("=== Audio Metadata for: " + file.getOriginalFilename() + " ===");
            for (String name : metadata.names()) {
                System.out.println(name + ": " + metadata.get(name));
            }
            
            String durStr = metadata.get("xmpDM:duration");
            if (durStr == null) durStr = metadata.get("duration");
            if (durStr == null) durStr = metadata.get("Content-Duration");
            if (durStr == null) durStr = metadata.get("xmpDM:audioSampleRate");
            
            if (durStr != null && !durStr.isEmpty()) {
                try {
                    double seconds = Double.parseDouble(durStr);
                    int milliseconds = (int) (seconds * 1000);
                    System.out.println("Parsed duration: " + milliseconds + "ms (" + seconds + "s)");
                    return milliseconds;
                } catch (NumberFormatException e) {
                    System.err.println("Could not parse duration value: " + durStr);
                }
            } else {
                System.err.println("No duration metadata found in file");
            }
            
        } catch (Exception e) {
            System.err.println("Duration parse failed for " + file.getOriginalFilename());
            e.printStackTrace();
        }
        
        System.err.println("Returning fallback duration of 180000ms (3 min)");
        return 180000;
    }

    // NOT CACHED - Video methods (you said video is coming later)
    public Video addVideo(String videoJson, MultipartFile file, MultipartFile artwork) {
        try {
            VideoUploadRequest req = objectMapper.readValue(videoJson, VideoUploadRequest.class);
            
            User artist = userRepository.findById(req.getArtistId())
                    .orElseThrow(() -> new IllegalArgumentException("Artist not found: " + req.getArtistId()));

            Genre genre = null;
            if (req.getGenreId() != null) {
                genre = genreRepository.findById(req.getGenreId())
                        .orElseThrow(() -> new IllegalArgumentException("Genre not found: " + req.getGenreId()));
            }

            Jurisdiction jurisdiction = null;
            if (req.getJurisdictionId() != null) {
                jurisdiction = jurisdictionRepository.findById(req.getJurisdictionId())
                        .orElseThrow(() -> new IllegalArgumentException("Jurisdiction not found: " + req.getJurisdictionId()));
            } else if (artist.getJurisdiction() != null) {
                jurisdiction = artist.getJurisdiction();
            } else {
                jurisdiction = jurisdictionRepository.findById(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                        .orElseThrow(() -> new IllegalArgumentException("Default jurisdiction not found"));
            }

            String videoUrl = fileStorageService.storeFile(file);

            String artworkUrl = null;
            if (artwork != null && !artwork.isEmpty()) {
                artworkUrl = fileStorageService.storeFile(artwork);
            }

            Integer duration = req.getDuration() != null ? req.getDuration() : computeDuration(file);

            Video video = new Video();
            video.setTitle(req.getTitle());
            video.setArtist(artist);
            video.setGenre(genre);
            video.setJurisdiction(jurisdiction);
            video.setDescription(req.getDescription());
            video.setDuration(duration);
            video.setVideoUrl(videoUrl);
            video.setArtworkUrl(artworkUrl);
            video.setScore(0);
            video.setLevel("silver");
            video.setCreatedAt(LocalDateTime.now());

            return videoRepository.save(video);
        } catch (IOException e) {
            throw new RuntimeException("JSON parse or file upload failed", e);
        }
    }

    // Delete song - EVICTS cache
    @CacheEvict(value = {"songs", "artists", "trending"}, allEntries = true)
    public void deleteSong(UUID songId) {
        songRepository.deleteById(songId);
    }

    // Update song (description and artwork only) - EVICTS cache
    @CacheEvict(value = {"songs", "artists", "trending"}, allEntries = true)
    public Song updateSong(UUID songId, String description, MultipartFile artwork, String lyrics) {
        Song song = songRepository.findById(songId)
            .orElseThrow(() -> new RuntimeException("Song not found: " + songId));

        if (description != null && !description.isBlank()) {
            song.setDescription(description.trim());
        }

        if (artwork != null && !artwork.isEmpty()) {
            try {
                String artworkUrl = fileStorageService.storeFile(artwork);
                song.setArtworkUrl(artworkUrl);
            } catch (Exception e) {
                throw new RuntimeException("Failed to upload artwork", e);
            }
        }

        if (lyrics != null) {
            song.setLyrics(lyrics);
        }

        return songRepository.save(song);
    }

    // NOT CACHED - video delete
    public void deleteVideo(UUID videoId) {
        videoRepository.deleteById(videoId);
    }

    // Play song - EVICTS song cache and trending cache because playsToday changes
    @CacheEvict(value = {"songs", "trending"}, allEntries = true)
    public void playSong(UUID songId, UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        // Update plays_today directly with native query
        LocalDate today = LocalDate.now();
        
        String updateQuery = """
            UPDATE songs 
            SET plays_today = CASE 
                WHEN last_play_reset_date < :today THEN 1
                ELSE plays_today + 1
            END,
            last_play_reset_date = :today
            WHERE song_id = :songId
            """;
        
        Query q = entityManager.createNativeQuery(updateQuery);
        q.setParameter("today", today);
        q.setParameter("songId", songId);
        q.executeUpdate();
        
        // Get song info for building SongPlay
        Song song = songRepository.findById(songId)
            .orElseThrow(() -> new RuntimeException("Song not found"));
        
        int durationInSeconds = song.getDuration() != null ? song.getDuration() / 1000 : 180;
        
        // FIXED: Explicitly set playedAt to ensure it's not null
        SongPlay play = SongPlay.builder()
            .song(song)
            .user(user)
            .playedAt(LocalDateTime.now())  
            .durationSecs(durationInSeconds)
            .build();
        songPlayRepository.save(play);

        // Increment artist's total_plays using a single query that gets artist_id from the song
        String incrementArtistPlays = """
            UPDATE users 
            SET total_plays = total_plays + 1 
            WHERE user_id = (SELECT artist_id FROM songs WHERE song_id = :songId)
            """;
        Query artistQuery = entityManager.createNativeQuery(incrementArtistPlays);
        artistQuery.setParameter("songId", songId);
        int rowsUpdated = artistQuery.executeUpdate();

        entityManager.flush();
        entityManager.clear();

        System.out.println(">>> total_plays increment: songId=" + songId + ", rowsUpdated=" + rowsUpdated);

        scoreUpdateService.onPlay(userId, songId, "song");
    }

    // NOT CACHED - video play
    public void playVideo(UUID videoId, UUID userId) {
        Optional<Video> optionalVideo = videoRepository.findById(videoId);
        Optional<User> optionalUser = userRepository.findById(userId);
        Video video = optionalVideo.orElseThrow(() -> new RuntimeException("Video not found"));
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        
        int durationInSeconds = video.getDuration() != null 
            ? video.getDuration() / 1000 
            : 180;
        
        VideoPlay play = VideoPlay.builder()
            .video(video)
            .user(user)
            .durationSecs(durationInSeconds)
            .build();
        videoPlayRepository.save(play);
        scoreUpdateService.onPlay(userId, videoId, "video");
    }

    // CACHED: Top songs by score (5 min TTL via CacheConfig)
    @Cacheable(value = "songs", key = "'top-score-' + #jurisdictionId + '-' + #limit")
    public List<Song> getTopSongsByJurisdiction(UUID jurisdictionId, int limit) {
        String query = """
            WITH RECURSIVE jurisdiction_hierarchy AS (
            SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
            UNION ALL
            SELECT j.jurisdiction_id FROM jurisdictions j
            INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
            )
            SELECT s.* FROM songs s
            INNER JOIN jurisdiction_hierarchy jh ON s.jurisdiction_id = jh.jurisdiction_id
            ORDER BY COALESCE(s.score, 0) DESC NULLS LAST, s.created_at DESC
            LIMIT :limit
            """;
        
        Query q = entityManager.createNativeQuery(query, Song.class);
        q.setParameter("jurisdictionId", jurisdictionId);
        q.setParameter("limit", limit);
        
        @SuppressWarnings("unchecked")
        List<Song> results = q.getResultList();
        
        results.forEach(this::ensurePlaysCurrentForSong);
        
        if (results.isEmpty()) {
            return getFallbackSongs(limit);
        }
        
        return results;
    }

    // CACHED: Trending songs by plays_today (shorter 1 min TTL via "trending" cache)
    @Cacheable(value = "trending", key = "'plays-today-' + #jurisdictionId + '-' + #limit")
    public List<Song> getTrendingSongsByJurisdiction(UUID jurisdictionId, int limit) {
        String query = """
            WITH RECURSIVE jurisdiction_hierarchy AS (
            SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
            UNION ALL
            SELECT j.jurisdiction_id FROM jurisdictions j
            INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
            )
            SELECT s.* FROM songs s
            INNER JOIN jurisdiction_hierarchy jh ON s.jurisdiction_id = jh.jurisdiction_id
            ORDER BY COALESCE(s.plays_today, 0) DESC, s.created_at DESC
            LIMIT :limit
            """;
        
        Query q = entityManager.createNativeQuery(query, Song.class);
        q.setParameter("jurisdictionId", jurisdictionId);
        q.setParameter("limit", limit);
        
        @SuppressWarnings("unchecked")
        List<Song> results = q.getResultList();
        
        results.forEach(this::ensurePlaysCurrentForSong);
        
        return results.isEmpty() ? getFallbackSongs(limit) : results;
    }

    // NOT CACHED - video methods
    public List<Video> getTopVideosByJurisdiction(UUID jurisdictionId, int limit) {
        String query = """
            WITH RECURSIVE jurisdiction_hierarchy AS (
            SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
            UNION ALL
            SELECT j.jurisdiction_id FROM jurisdictions j
            INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
            )
            SELECT v.* FROM videos v
            INNER JOIN jurisdiction_hierarchy jh ON v.jurisdiction_id = jh.jurisdiction_id
            ORDER BY COALESCE(v.score, 0) DESC NULLS LAST, v.created_at DESC
            LIMIT :limit
            """;
        
        Query q = entityManager.createNativeQuery(query, Video.class);
        q.setParameter("jurisdictionId", jurisdictionId);
        q.setParameter("limit", limit);
        
        @SuppressWarnings("unchecked")
        List<Video> results = q.getResultList();
        return results.isEmpty() ? getFallbackVideos(limit) : results;
    }

    // NOT CACHED - private helper method
    private void ensurePlaysCurrentForSong(Song song) {
        Long playCount = songPlayRepository.countTotalPlaysBySongId(song.getSongId());
        song.setPlayCount(playCount != null ? playCount : 0L);
    }

    // NOT CACHED - private fallback method
    private List<Song> getFallbackSongs(int limit) {
        List<Song> songs = songRepository.findAll(Sort.by(Sort.Direction.ASC, "songId"))
            .stream()
            .limit(limit)
            .collect(Collectors.toList());
        
        songs.forEach(song -> {
            Long playCount = songPlayRepository.countTotalPlaysBySongId(song.getSongId());
            song.setPlayCount(playCount != null ? playCount : 0L);
        });
        
        return songs;
    }

    // NOT CACHED - private fallback method
    private List<Video> getFallbackVideos(int limit) {
        return videoRepository.findAll(Sort.by(Sort.Direction.ASC, "videoId"))
            .stream()
            .limit(limit)
            .collect(Collectors.toList());
    }

    // ========== UPDATED: CACHED Artist's songs with likes and plays ==========
    @Cacheable(value = "artists", key = "'songs-' + #artistId")
    public List<Song> getSongsByArtist(UUID artistId) {
        String sql = """
            SELECT 
                s.*,
                COALESCE(like_counts.likes, 0) as likes,
                COALESCE(play_counts.plays, 0) as plays
            FROM songs s
            LEFT JOIN (
                SELECT media_id, COUNT(*) as likes
                FROM likes
                WHERE media_type = 'song'
                GROUP BY media_id
            ) like_counts ON s.song_id = like_counts.media_id
            LEFT JOIN (
                SELECT song_id, COUNT(*) as plays
                FROM song_plays
                GROUP BY song_id
            ) play_counts ON s.song_id = play_counts.song_id
            WHERE s.artist_id = ?
            ORDER BY s.created_at DESC
        """;
        
        return jdbcTemplate.query(sql, new SongWithStatsRowMapper(), artistId);
    }

    // NOT CACHED - video methods
    public List<Video> getVideosByArtist(UUID artistId) {
        return videoRepository.findByArtistId(artistId);
    }

    // ========== UPDATED: CACHED Individual song lookup with likes and plays ==========
    public Song getSongById(UUID songId) {
         // Use a fresh fetch to ensure we aren't getting a 'cleared' or 'detached' entity
        Song song = songRepository.findById(songId)
            .orElseThrow(() -> new RuntimeException("Song not found: " + songId));
        
        try {
            // Set play count safely
            Long playCount = songPlayRepository.countTotalPlaysBySongId(songId);
            song.setPlayCount(playCount != null ? playCount : 0L);
            
            // Set likes count safely
            int likesCount = getLikeCount(songId);
            song.setLikes(likesCount);
            
            // Ensure Artist is initialized to prevent LazyInitializationException
            if (song.getArtist() != null) {
                song.getArtist().getUsername(); // Trigger load
            }
        } catch (Exception e) {
            System.err.println("Error decorating song with stats: " + e.getMessage());
            // We still return the song even if stats fail, so the page doesn't go blank
        }
        
        return song;
    }

    // NOT CACHED - video method
    public Video getVideoById(UUID videoId) {
        return videoRepository.findById(videoId)
            .orElseThrow(() -> new RuntimeException("Video not found: " + videoId));
    }

    // CACHED: New releases (5 min TTL)
    @Cacheable(value = "songs", key = "'new-' + #jurisdictionId + '-' + #limit")
    public List<Song> getNewSongsByJurisdiction(UUID jurisdictionId, int limit) {
        String query = """
            WITH RECURSIVE jurisdiction_hierarchy AS (
            SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
            UNION ALL
            SELECT j.jurisdiction_id FROM jurisdictions j
            INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
            )
            SELECT s.* FROM songs s
            INNER JOIN jurisdiction_hierarchy jh ON s.jurisdiction_id = jh.jurisdiction_id
            ORDER BY COALESCE(s.created_at, '1900-01-01T00:00:00') DESC
            LIMIT :limit
            """;
        
        Query q = entityManager.createNativeQuery(query, Song.class);
        q.setParameter("jurisdictionId", jurisdictionId);
        q.setParameter("limit", limit);
        
        @SuppressWarnings("unchecked")
        List<Song> results = q.getResultList();
        
        results.forEach(song -> {
            Long playCount = songPlayRepository.countTotalPlaysBySongId(song.getSongId());
            song.setPlayCount(playCount != null ? playCount : 0L);
        });
        
        if (results.isEmpty()) {
            return getFallbackSongs(limit);
        }
        
        return results;
    }

    // ========== LIKES SERVICE METHODS (NEW) ==========
    
    /**
     * Like a song
     * @param songId The song to like
     * @param userId The user liking the song
     * @return true if liked, false if already liked
     */

    @Transactional
    @CacheEvict(value = {"songs", "artists"}, allEntries = true)
    public boolean likeSong(UUID songId, UUID userId) {
        // Check if already liked
        String checkSql = "SELECT COUNT(*) FROM likes WHERE user_id = ? AND media_id = ? AND media_type = 'song'";
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, userId, songId);
        
        if (count != null && count > 0) {
            return false; // Already liked
        }
        
        // Insert like with created_at
        String insertSql = "INSERT INTO likes (like_id, user_id, media_id, media_type, created_at) VALUES (?, ?, ?, 'song', NOW())";
        jdbcTemplate.update(insertSql, UUID.randomUUID(), userId, songId);
        
        // Award points: Song +2, Artist +1, User +1
        scoreUpdateService.onLike(userId, songId);
        
        System.out.println("✓ Like created: User " + userId + " liked Song " + songId + " (points awarded)");
        
        return true;
    }
    
    /**
     * Unlike a song
     * @param songId The song to unlike
     * @param userId The user unliking the song
     * @return true if unliked, false if wasn't liked
     */
    @Transactional
    @CacheEvict(value = {"songs", "artists"}, allEntries = true)
    public boolean unlikeSong(UUID songId, UUID userId) {
        String deleteSql = "DELETE FROM likes WHERE user_id = ? AND media_id = ? AND media_type = 'song'";
        int rowsAffected = jdbcTemplate.update(deleteSql, userId, songId);
        
        // Note: We do NOT deduct points when unliking
        // Points represent historical engagement value and remain
        
        if (rowsAffected > 0) {
            System.out.println("✓ Like removed: User " + userId + " unliked Song " + songId + " (points retained)");
        }
        
        return rowsAffected > 0;
    }
    
    /**
     * Check if a user has liked a song
     * @param songId The song ID
     * @param userId The user ID
     * @return true if liked, false otherwise
     */
    public boolean isLiked(UUID songId, UUID userId) {
        String sql = "SELECT COUNT(*) FROM likes WHERE user_id = ? AND media_id = ? AND media_type = 'song'";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, userId, songId);
        return count != null && count > 0;
    }
    
    /**
     * Get the total like count for a song
     * @param songId The song ID
     * @return The number of likes
     */
    public int getLikeCount(UUID songId) {
        String sql = "SELECT COUNT(*) FROM likes WHERE media_id = ? AND media_type = 'song'";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, songId);
        return count != null ? count : 0;
    }
    
    // ========== END LIKES SERVICE METHODS ==========

    // ========== ROW MAPPER FOR SONGS WITH STATS (NEW) ==========
    
    /**
     * Custom RowMapper to map database results to Song entity with stats
     */
    private class SongWithStatsRowMapper implements RowMapper<Song> {
        @Override
        public Song mapRow(ResultSet rs, int rowNum) throws SQLException {
            Song song = new Song();
            
            // Map all existing Song fields
            song.setSongId((UUID) rs.getObject("song_id"));
            song.setTitle(rs.getString("title"));
            song.setDescription(rs.getString("description"));
            song.setFileUrl(rs.getString("file_url"));
            song.setArtworkUrl(rs.getString("artwork_url"));
            song.setScore(rs.getInt("score"));
            song.setLevel(rs.getString("level"));
            song.setExplicit(rs.getBoolean("explicit"));
            song.setLyrics(rs.getString("lyrics"));
            song.setPlaysToday(rs.getInt("plays_today"));
            
            // Handle duration (might be null)
            int duration = rs.getInt("duration");
            if (!rs.wasNull()) {
                song.setDuration(duration);
            }
            
            // Handle LocalDate
            java.sql.Date lastPlayResetDate = rs.getDate("last_play_reset_date");
            if (lastPlayResetDate != null) {
                song.setLastPlayResetDate(lastPlayResetDate.toLocalDate());
            }
            
            // Handle LocalDateTime
            java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
            if (createdAt != null) {
                song.setCreatedAt(createdAt.toLocalDateTime());
            }
            
            // Load relationships (@ManyToOne fields) using repository lookups
            UUID artistId = (UUID) rs.getObject("artist_id");
            UUID genreId = (UUID) rs.getObject("genre_id");
            UUID jurisdictionId = (UUID) rs.getObject("jurisdiction_id");
            
            if (artistId != null) {
                userRepository.findById(artistId).ifPresent(song::setArtist);
            }
            if (genreId != null) {
                genreRepository.findById(genreId).ifPresent(song::setGenre);
            }
            if (jurisdictionId != null) {
                jurisdictionRepository.findById(jurisdictionId).ifPresent(song::setJurisdiction);
            }
            
            // Set the NEW transient fields for likes and plays
            song.setLikes(rs.getInt("likes"));
            song.setPlayCount((long) rs.getInt("plays"));
            
            return song;
        }
    }
}