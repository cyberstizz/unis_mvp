package com.unis.controller;

import com.unis.entity.Song;
import com.unis.entity.Video;
import com.unis.service.MediaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.UUID;

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
            @RequestPart(value = "artwork", required = false) MultipartFile artwork) {  // Optional artwork
        Song saved = mediaService.addSong(songJson, file, artwork);  // Pass all 3 args
        return ResponseEntity.ok(saved);
    }

    // POST /api/v1/media/video (add video, page 7)
    @PostMapping(value = "/video", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Video> addVideo(
            @RequestPart("video") String videoJson, 
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "artwork", required = false) MultipartFile artwork) {  // Optional artwork
        Video saved = mediaService.addVideo(videoJson, file, artwork);  // Pass all 3 args
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

    // GET /api/v1/media/songs/jurisdiction/{id}?limit=3 (top songs, page 3)
    @GetMapping("/songs/jurisdiction/{jurisdictionId}")
    public ResponseEntity<List<Song>> getTopSongsByJurisdiction(@PathVariable UUID jurisdictionId, @RequestParam(defaultValue = "3") int limit) {
        List<Song> songs = mediaService.getTopSongsByJurisdiction(jurisdictionId, limit);
        return ResponseEntity.ok(songs);
    }

    // GET /api/v1/media/videos/jurisdiction/{id}?limit=3 (top videos, page 3)
    @GetMapping("/videos/jurisdiction/{jurisdictionId}")
    public ResponseEntity<List<Video>> getTopVideosByJurisdiction(@PathVariable UUID jurisdictionId, @RequestParam(defaultValue = "3") int limit) {
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
}