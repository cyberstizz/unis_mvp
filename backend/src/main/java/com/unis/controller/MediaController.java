package com.unis.controller;

import com.unis.entity.Song;
import com.unis.entity.Video;
import com.unis.service.MediaService;
import lombok.extern.slf4j.Slf4j;  
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {
    @Autowired
    private MediaService mediaService;

    // POST /api/v1/media/song (add song, page 7)
    @PostMapping(value = "/song", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Song> addSong(
            @RequestPart("song") String songJson, 
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "artwork", required = false) MultipartFile artwork) {
        Song saved = mediaService.addSong(songJson, file, artwork);
        return ResponseEntity.ok(saved);
    }

    // POST /api/v1/media/video (add video, page 7)
    @PostMapping(value = "/video", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Video> addVideo(
            @RequestPart("video") String videoJson, 
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "artwork", required = false) MultipartFile artwork) {
        Video saved = mediaService.addVideo(videoJson, file, artwork);
        return ResponseEntity.ok(saved);
    }

    // DELETE /api/v1/media/song/{id} (delete song, page 7)
    @DeleteMapping("/song/{songId}")
    public ResponseEntity<Void> deleteSong(@PathVariable UUID songId) {
        mediaService.deleteSong(songId);
        return ResponseEntity.ok().build();
    }

    // DELETE /api/v1/media/video/{id} (delete video, page 7)
    @DeleteMapping("/video/{videoId}")
    public ResponseEntity<Void> deleteVideo(@PathVariable UUID videoId) {
        mediaService.deleteVideo(videoId);
        return ResponseEntity.ok().build();
    }

    // POST /api/v1/media/song/{id}/play?userId={userId} (play song, pages 1,3,11)
    @PostMapping("/song/{songId}/play")
    public ResponseEntity<Void> playSong(@PathVariable UUID songId, @RequestParam UUID userId) {
        mediaService.playSong(songId, userId);
        return ResponseEntity.ok().build();
    }

    // POST /api/v1/media/video/{id}/play?userId={userId} (play video, pages 1,3,11)
    @PostMapping("/video/{videoId}/play")
    public ResponseEntity<Void> playVideo(@PathVariable UUID videoId, @RequestParam UUID userId) {
        mediaService.playVideo(videoId, userId);
        return ResponseEntity.ok().build();
    }

    // GET /api/v1/media/songs/jurisdiction/{id}?limit=3 (top songs by SCORE, page 3)
    @GetMapping("/songs/jurisdiction/{jurisdictionId}")
    public ResponseEntity<List<Song>> getTopSongsByJurisdiction(
            @PathVariable UUID jurisdictionId, 
            @RequestParam(defaultValue = "3") int limit) {
        List<Song> songs = mediaService.getTopSongsByJurisdiction(jurisdictionId, limit);
        return ResponseEntity.ok(songs);
    }

    // GET /api/v1/media/videos/jurisdiction/{id}?limit=3 (top videos, page 3)
    @GetMapping("/videos/jurisdiction/{jurisdictionId}")
    public ResponseEntity<List<Video>> getTopVideosByJurisdiction(
            @PathVariable UUID jurisdictionId, 
            @RequestParam(defaultValue = "3") int limit) {
        List<Video> videos = mediaService.getTopVideosByJurisdiction(jurisdictionId, limit);
        return ResponseEntity.ok(videos);
    }

    // GET /api/v1/media/songs/artist/{artistId} (artist's songs, page 7)
    @GetMapping("/songs/artist/{artistId}")
    public ResponseEntity<List<Song>> getSongsByArtist(@PathVariable UUID artistId) {
        List<Song> songs = mediaService.getSongsByArtist(artistId);
        return ResponseEntity.ok(songs);
    }

    // GET /api/v1/media/videos/artist/{artistId} (artist's videos, page 7)
    @GetMapping("/videos/artist/{artistId}")
    public ResponseEntity<List<Video>> getVideosByArtist(@PathVariable UUID artistId) {
        List<Video> videos = mediaService.getVideosByArtist(artistId);
        return ResponseEntity.ok(videos);
    }

    // GET /api/v1/media/song/{songId} (get single song)
    @GetMapping("/song/{songId}")
    public ResponseEntity<Song> getSong(@PathVariable UUID songId) {
        Song song = mediaService.getSongById(songId);
        return ResponseEntity.ok(song);
    }

    // GET /api/v1/media/song/{songId}/lyrics (get song lyrics - NEW)
    @GetMapping("/song/{songId}/lyrics")
    public ResponseEntity<Map<String, Object>> getSongLyrics(@PathVariable UUID songId) {
        try {
            Song song = mediaService.getSongById(songId);
            Map<String, Object> response = new HashMap<>();
            response.put("songId", song.getSongId());
            response.put("title", song.getTitle());
            response.put("artist", song.getArtist().getUsername());
            response.put("lyrics", song.getLyrics());
            response.put("explicit", song.getExplicit());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    // GET /api/v1/media/trending (mixed songs + videos by SCORE)
    // KEPT YOUR ORIGINAL - This is for your existing feed
    @GetMapping("/trending")
    public ResponseEntity<List<Object>> getTrendingMedia(
            @RequestParam UUID jurisdictionId, 
            @RequestParam(defaultValue = "5") int limit) {
        List<Song> topSongs = mediaService.getTopSongsByJurisdiction(jurisdictionId, limit);
        List<Video> topVideos = mediaService.getTopVideosByJurisdiction(jurisdictionId, limit);
        List<Object> mixed = new ArrayList<>();
        mixed.addAll(topSongs);
        mixed.addAll(topVideos);
        mixed.sort(Comparator.comparing((Object o) -> 
            -(o instanceof Song ? ((Song) o).getScore() : ((Video) o).getScore())));
        return ResponseEntity.ok(mixed.stream().limit(limit).collect(Collectors.toList()));
    }

    // GET /api/v1/media/trending/today (songs by plays_today - NEW)
    // This is the NEW endpoint for today's trending based on plays_today
    @GetMapping("/trending/today")
    public ResponseEntity<List<Song>> getTrendingToday(
            @RequestParam UUID jurisdictionId,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            List<Song> trendingSongs = mediaService.getTrendingSongsByJurisdiction(jurisdictionId, limit);
            return ResponseEntity.ok(trendingSongs);
        } catch (Exception e) {
            log.error("Trending today query failed:", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // GET /api/v1/media/new (newest songs)
    @GetMapping("/new")
    public ResponseEntity<List<Song>> getNewMedia(
            @RequestParam UUID jurisdictionId, 
            @RequestParam(defaultValue = "5") int limit) {
        try {
            List<Song> newSongs = mediaService.getNewSongsByJurisdiction(jurisdictionId, limit);
            return ResponseEntity.ok(newSongs);
        } catch (Exception e) {
            log.error("New media query failed, falling back to trending:", e);
            // Fallback: Get trending, filter to songs only
            List<Object> trendingMixed = getTrendingMedia(jurisdictionId, limit).getBody();
            List<Song> fallbackSongs = trendingMixed.stream()
                .filter(o -> o instanceof Song)
                .map(o -> (Song) o)
                .limit(limit)
                .collect(Collectors.toList());
            return ResponseEntity.ok(fallbackSongs);
        }
    }
}