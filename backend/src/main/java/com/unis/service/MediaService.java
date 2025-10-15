package com.unis.service;

import com.unis.entity.Song;
import com.unis.entity.Video;
import com.unis.entity.VideoPlay;
import com.unis.entity.SongPlay;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;  // <-- Added for orElseThrow
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
        song.setFileUrl("/uploads/" + file.getOriginalFilename());  // Placeholder
        song.setCreatedAt(LocalDateTime.now());
        return songRepository.save(song);
    }

    // Similar addVideo
    public Video addVideo(Video video, MultipartFile file) {
        video.setVideoUrl("/uploads/" + file.getOriginalFilename());
        video.setCreatedAt(LocalDateTime.now());
        return videoRepository.save(video);
    }

    // Delete song (page 7)
    public void deleteSong(UUID songId) {
        songRepository.deleteById(songId);
    }

    // Similar deleteVideo
    public void deleteVideo(UUID videoId) {
        videoRepository.deleteById(videoId);
    }

    // Play song (page 1/3/11, increments play + score)
    public void playSong(UUID songId, UUID userId) {
        Song song = songRepository.findById(songId).orElseThrow(() -> new RuntimeException("Song not found"));
        User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));
        SongPlay play = SongPlay.builder()
            .song(song)
            .user(user)
            .durationSecs(180)  // Placeholder
            .build();
        songPlayRepository.save(play);
        scoreUpdateService.onPlay(userId, songId, "song");
    }

    // Similar playVideo
    public void playVideo(UUID videoId, UUID userId) {
        Video video = videoRepository.findById(videoId).orElseThrow(() -> new RuntimeException("Video not found"));
        User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));
        VideoPlay play = VideoPlay.builder()
            .video(video)
            .user(user)
            .durationSecs(180)  // Placeholder
            .build();
        videoPlayRepository.save(play);
        scoreUpdateService.onPlay(userId, videoId, "video");
    }

    // Get top songs by jurisdiction (page 3 find, page 1 feed)
    public List<Song> getTopSongsByJurisdiction(UUID jurisdictionId, int limit) {
        List<Song> songs = songRepository.findTopByJurisdiction(jurisdictionId);
        return songs.subList(0, Math.min(limit, songs.size()));  // Safe sublist
    }

    // Artist media list (page 7 dashboard)
    public List<Song> getSongsByArtist(UUID artistId) {
        return songRepository.findByArtistId(artistId);
    }

    // Similar getVideosByArtist
    public List<Video> getVideosByArtist(UUID artistId) {
        return videoRepository.findByArtistId(artistId);
    }
}