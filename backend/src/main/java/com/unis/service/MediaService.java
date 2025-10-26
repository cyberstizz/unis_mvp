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
import com.unis.repository.SongRepository;
import com.unis.repository.VideoRepository;
import com.unis.repository.SongPlayRepository;
import com.unis.repository.VideoPlayRepository;
import com.unis.repository.UserRepository;
import com.unis.repository.GenreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private ScoreUpdateService scoreUpdateService;

    @Autowired
    private FileStorageService fileStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();  // For JSON parse

    // Add song (page 7 artist dashboard)
    public Song addSong(String songJson, MultipartFile file) {
        try {
            SongUploadRequest req = objectMapper.readValue(songJson, SongUploadRequest.class);
            // Resolve artist
            User artist = userRepository.findById(req.getArtistId())
                    .orElseThrow(() -> new IllegalArgumentException("Artist not found: " + req.getArtistId()));

            // Resolve genre (optional)
            Genre genre = null;
            if (req.getGenreId() != null) {
                genre = genreRepository.findById(req.getGenreId())
                        .orElseThrow(() -> new IllegalArgumentException("Genre not found: " + req.getGenreId()));
            }

            // Save file and get URL
            String fileUrl = fileStorageService.storeFile(file);

            // Build Song entity
            Song song = Song.builder()
                    .title(req.getTitle())
                    .artist(artist)
                    .genre(genre)
                    .description(req.getDescription())
                    .duration(req.getDuration())
                    .fileUrl(fileUrl)
                    .build();

            return songRepository.save(song);
        } catch (IOException e) {
            throw new RuntimeException("JSON parse or file upload failed", e);
        }
    }

    // Add video (page 7 artist dashboard)
    public Video addVideo(String videoJson, MultipartFile file) {
        try {
            VideoUploadRequest req = objectMapper.readValue(videoJson, VideoUploadRequest.class);
            // Resolve artist
            User artist = userRepository.findById(req.getArtistId())
                    .orElseThrow(() -> new IllegalArgumentException("Artist not found: " + req.getArtistId()));

            // Resolve genre (optional)
            Genre genre = null;
            if (req.getGenreId() != null) {
                genre = genreRepository.findById(req.getGenreId())
                        .orElseThrow(() -> new IllegalArgumentException("Genre not found: " + req.getGenreId()));
            }

            // Save file and get URL
            String fileUrl = fileStorageService.storeFile(file);

            // Build Video entity
            Video video = Video.builder()
                    .title(req.getTitle())
                    .artist(artist)
                    .genre(genre)
                    .description(req.getDescription())
                    .duration(req.getDuration())
                    .videoUrl(fileUrl)
                    .build();

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

   // Get top songs by jurisdiction (pages 3,1 feed)
    public List<Song> getTopSongsByJurisdiction(UUID jurisdictionId, int limit) {
        List<Song> songs = songRepository.findTopByJurisdictionWithHierarchy(jurisdictionId, limit);
        return songs;
    }

    // Get top videos by jurisdiction (pages 3,1 feed)
    public List<Video> getTopVideosByJurisdiction(UUID jurisdictionId, int limit) {
        List<Video> videos = videoRepository.findTopByJurisdictionWithHierarchy(jurisdictionId, limit);
        return videos;
    }

    // Artist's songs (page 7 dashboard)
    public List<Song> getSongsByArtist(UUID artistId) {
        return songRepository.findByArtistId(artistId);
    }

    // Artist's videos (page 7 dashboard)
    public List<Video> getVideosByArtist(UUID artistId) {
        return videoRepository.findByArtistId(artistId);
    }
}