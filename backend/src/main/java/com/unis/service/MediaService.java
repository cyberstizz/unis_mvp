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
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
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
    private ScoreUpdateService scoreUpdateService;

    @Autowired
    private FileStorageService fileStorageService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Add song (page 7 artist dashboard)
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

            return songRepository.save(song);
        } catch (IOException e) {
            throw new RuntimeException("JSON parse or file upload failed", e);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid upload data: " + e.getMessage(), e);
        }
    }

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

    // Add video (page 7 artist dashboard)
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

    public void deleteSong(UUID songId) {
        songRepository.deleteById(songId);
    }

    public void deleteVideo(UUID videoId) {
        videoRepository.deleteById(videoId);
    }

    // Play song - inserts play, triggers score +1
    public void playSong(UUID songId, UUID userId) {
        Optional<Song> optionalSong = songRepository.findById(songId);
        Optional<User> optionalUser = userRepository.findById(userId);
        Song song = optionalSong.orElseThrow(() -> new RuntimeException("Song not found"));
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        
        SongPlay play = SongPlay.builder()
            .song(song)
            .user(user)
            .durationSecs(180)
            .build();
        songPlayRepository.save(play);
        scoreUpdateService.onPlay(userId, songId, "song");
    }

    // Play video - inserts play, triggers score +1
    public void playVideo(UUID videoId, UUID userId) {
        Optional<Video> optionalVideo = videoRepository.findById(videoId);
        Optional<User> optionalUser = userRepository.findById(userId);
        Video video = optionalVideo.orElseThrow(() -> new RuntimeException("Video not found"));
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        
        VideoPlay play = VideoPlay.builder()
            .video(video)
            .user(user)
            .durationSecs(180)
            .build();
        videoPlayRepository.save(play);
        scoreUpdateService.onPlay(userId, videoId, "video");
    }

    // UPDATED: Get top songs by score in jurisdiction + hierarchy
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
        
        // NEW: Add play counts to each song
        results.forEach(song -> {
            Long playCount = songPlayRepository.countTotalPlaysBySongId(song.getSongId());
            song.setPlayCount(playCount != null ? playCount : 0L);
        });
        
        if (results.isEmpty()) {
            return getFallbackSongs(limit);
        }
        
        return results;
    }

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

    // UPDATED: Fallback with play counts
    private List<Song> getFallbackSongs(int limit) {
        List<Song> songs = songRepository.findAll(Sort.by(Sort.Direction.ASC, "songId"))
            .stream()
            .limit(limit)
            .collect(Collectors.toList());
        
        // Add play counts
        songs.forEach(song -> {
            Long playCount = songPlayRepository.countTotalPlaysBySongId(song.getSongId());
            song.setPlayCount(playCount != null ? playCount : 0L);
        });
        
        return songs;
    }

    private List<Video> getFallbackVideos(int limit) {
        return videoRepository.findAll(Sort.by(Sort.Direction.ASC, "videoId"))
            .stream()
            .limit(limit)
            .collect(Collectors.toList());
    }

    // UPDATED: Artist's songs with play counts
    public List<Song> getSongsByArtist(UUID artistId) {
        List<Song> songs = songRepository.findByArtistId(artistId);
        
        // Add play counts to each song
        songs.forEach(song -> {
            Long playCount = songPlayRepository.countTotalPlaysBySongId(song.getSongId());
            song.setPlayCount(playCount != null ? playCount : 0L);
        });
        
        return songs;
    }

    public List<Video> getVideosByArtist(UUID artistId) {
        return videoRepository.findByArtistId(artistId);
    }

    // UPDATED: Get single song by ID with play count
    public Song getSongById(UUID songId) {
        Song song = songRepository.findById(songId)
            .orElseThrow(() -> new RuntimeException("Song not found: " + songId));
        
        // Calculate actual play count
        Long playCount = songPlayRepository.countTotalPlaysBySongId(songId);
        song.setPlayCount(playCount != null ? playCount : 0L);
        
        return song;
    }

    public Video getVideoById(UUID videoId) {
        return videoRepository.findById(videoId)
            .orElseThrow(() -> new RuntimeException("Video not found: " + videoId));
    }

    // UPDATED: Get newest songs with play counts
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
        
        // NEW: Add play counts to each song
        results.forEach(song -> {
            Long playCount = songPlayRepository.countTotalPlaysBySongId(song.getSongId());
            song.setPlayCount(playCount != null ? playCount : 0L);
        });
        
        if (results.isEmpty()) {
            return getFallbackSongs(limit);
        }
        
        return results;
    }
}