package com.unis.service;

import com.fasterxml.jackson.databind.ObjectMapper;  // For JSON parse
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
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.audio.AudioParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.helpers.DefaultHandler;

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
    // for json parse
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
            // Fallback: Default to Harlem root (000...000001)
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
        return 180000;  // 3 min fallback
    }
    try (InputStream is = file.getInputStream()) {
        Metadata metadata = new Metadata();
        new AudioParser().parse(is, new DefaultHandler(), metadata, new ParseContext());
        String durStr = metadata.get("duration");
        if (durStr != null && !durStr.isEmpty()) {
            double seconds = Double.parseDouble(durStr);
            return (int) (seconds * 1000);  
        }
    } catch (Exception e) {
        System.err.println("Duration parse failed for " + file.getOriginalFilename() + ": " + e.getMessage());
    }
    return 180000;  // Fallback on error
}

    // Add video (page 7 artist dashboard)
    public Video addVideo(String videoJson, MultipartFile file, MultipartFile artwork) {
        try {
            VideoUploadRequest req = objectMapper.readValue(videoJson, VideoUploadRequest.class);
            // Resolve artist
            User artist = userRepository.findById(req.getArtistId())
                    .orElseThrow(() -> new IllegalArgumentException("Artist not found: " + req.getArtistId()));

            // Resolve genre 
            Genre genre = null;
            if (req.getGenreId() != null) {
                genre = genreRepository.findById(req.getGenreId())
                        .orElseThrow(() -> new IllegalArgumentException("Genre not found: " + req.getGenreId()));
            }

             // Resolve jurisdiction
            Jurisdiction jurisdiction = null;
            if (req.getJurisdictionId() != null) {
                jurisdiction = jurisdictionRepository.findById(req.getJurisdictionId())
                        .orElseThrow(() -> new IllegalArgumentException("Jurisdiction not found: " + req.getJurisdictionId()));
            } else if (artist.getJurisdiction() != null) {
                jurisdiction = artist.getJurisdiction();
            } else {
                // Fallback: Default to Harlem root (000...000001)
                jurisdiction = jurisdictionRepository.findById(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                        .orElseThrow(() -> new IllegalArgumentException("Default jurisdiction not found"));
            }

            // Save video file and get URL
            String videoUrl = fileStorageService.storeFile(file);

            // Save artwork file if present and get URL
            String artworkUrl = null;
            if (artwork != null && !artwork.isEmpty()) {
                artworkUrl = fileStorageService.storeFile(artwork);
            }

            // Duration 
            Integer duration = req.getDuration() != null ? req.getDuration() : computeDuration(file);

            // Build Video entity
            Video video = new Video();
                video.setTitle(req.getTitle());
                video.setArtist(artist);
                video.setGenre(genre);
                video.setJurisdiction(jurisdiction);
                video.setDescription(req.getDescription());
                video.setDuration(duration);
                video.setVideoUrl(videoUrl);
                video.setArtworkUrl(artworkUrl);
                video.setScore(0);  // Explicit
                video.setLevel("silver");  // Explicit
                video.setCreatedAt(LocalDateTime.now());  // Explicit

                return videoRepository.save(video);
        } catch (IOException e) {
            throw new RuntimeException("JSON parse or file upload failed", e);
        }
    }

    // Delete song (page 7)
    public void deleteSong(UUID songId) {
        songRepository.deleteById(songId);
    }

    // Delete video (page 7)
    public void deleteVideo(UUID videoId) {
        videoRepository.deleteById(videoId);
    }

    // Play song (pages 1,3,11—inserts play, triggers score +1)
    public void playSong(UUID songId, UUID userId) {
        Optional<Song> optionalSong = songRepository.findById(songId);
        Optional<User> optionalUser = userRepository.findById(userId);
        Song song = optionalSong.orElseThrow(() -> new RuntimeException("Song not found"));
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        SongPlay play = SongPlay.builder()
            .song(song)
            .user(user)
            .durationSecs(180)  // Placeholder
            .build();
        songPlayRepository.save(play);
        scoreUpdateService.onPlay(userId, songId, "song");
    }

    // Play video (pages 1,3,11—inserts play, triggers score +1)
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

    // Get top songs by score in jurisdiction + hierarchy (page 3, feed trending/new)
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
        return results.isEmpty() ? getFallbackSongs(limit) : results;  // Fallback if no data
    }

    // Symmetric for videos
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
        return results.isEmpty() ? getFallbackVideos(limit) : results;  // Fallback if no data
    }

    // Private helpers: Fallback to first N by ID (for launch sparsity)
    private List<Song> getFallbackSongs(int limit) {
        return songRepository.findAll(Sort.by(Sort.Direction.ASC, "songId")).stream().limit(limit).collect(Collectors.toList());
    }

    private List<Video> getFallbackVideos(int limit) {
        return videoRepository.findAll(Sort.by(Sort.Direction.ASC, "videoId")).stream().limit(limit).collect(Collectors.toList());
    }

    // Artist's songs (page 7 dashboard)
    public List<Song> getSongsByArtist(UUID artistId) {
        return songRepository.findByArtistId(artistId);
    }

    // Artist's videos (page 7 dashboard)
    public List<Video> getVideosByArtist(UUID artistId) {
        return videoRepository.findByArtistId(artistId);
    }

    // Get single song by ID (for song detail page)
    public Song getSongById(UUID songId) {
        return songRepository.findById(songId)
            .orElseThrow(() -> new RuntimeException("Song not found: " + songId));
    }

    // Get single video by ID (for video detail page)
    public Video getVideoById(UUID videoId) {
        return videoRepository.findById(videoId)
            .orElseThrow(() -> new RuntimeException("Video not found: " + videoId));
    }
}