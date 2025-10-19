package com.unis.service;

import com.unis.entity.Song;
import com.unis.entity.Video;
import com.unis.entity.SongPlay;
import com.unis.entity.VideoPlay;
import com.unis.entity.User;
import com.unis.repository.SongRepository;
import com.unis.repository.VideoRepository;
import com.unis.repository.SongPlayRepository;
import com.unis.repository.VideoPlayRepository;
import com.unis.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
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
    private ScoreUpdateService scoreUpdateService;

    // Add song (page 7 artist dashboard)
    public Song addSong(Song song, MultipartFile file) {
        song.setFileUrl("/uploads/" + file.getOriginalFilename());  // Placeholder (S3 later)
        song.setCreatedAt(java.time.LocalDateTime.now());
        return songRepository.save(song);
    }

    // Add video (page 7 artist dashboard)
    public Video addVideo(Video video, MultipartFile file) {
        video.setVideoUrl("/uploads/" + file.getOriginalFilename());
        video.setCreatedAt(java.time.LocalDateTime.now());
        return videoRepository.save(video);
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
            .durationSecs(180)  // Placeholder
            .build();
        videoPlayRepository.save(play);
        scoreUpdateService.onPlay(userId, videoId, "video");
    }

    // Get top songs by jurisdiction (pages 3,1 feed)
    public List<Song> getTopSongsByJurisdiction(UUID jurisdictionId, int limit) {
        List<Song> songs = songRepository.findTopByJurisdiction(jurisdictionId);
        return songs.subList(0, Math.min(limit, songs.size()));
    }

    // Get top videos by jurisdiction (pages 3,1 feed)
    public List<Video> getTopVideosByJurisdiction(UUID jurisdictionId, int limit) {
        List<Video> videos = videoRepository.findTopByJurisdiction(jurisdictionId);
        return videos.subList(0, Math.min(limit, videos.size()));
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