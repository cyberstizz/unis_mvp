package com.unis.controller;

import com.unis.entity.Song;
// import com.unis.entity.Video;
import com.unis.service.MediaService;
import org.springframework.beans.factory.annotation.Autowired;
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
    @PostMapping("/song")
    public ResponseEntity<Song> addSong(@RequestPart("song") Song song, @RequestPart("file") MultipartFile file) {
        Song saved = mediaService.addSong(song, file);
        return ResponseEntity.ok(saved);
    }

    // DELETE /api/v1/media/song/{id} (delete, page 7)
    @DeleteMapping("/song/{songId}")
    public ResponseEntity<Void> deleteSong(@PathVariable UUID songId) {
        mediaService.deleteSong(songId);
        return ResponseEntity.ok().build();
    }

    // POST /api/v1/media/song/{id}/play (play, pages 1/3/11)
    @PostMapping("/song/{songId}/play")
    public ResponseEntity<Void> playSong(@PathVariable UUID songId, @RequestParam UUID userId) {
        mediaService.playSong(songId, userId);
        return ResponseEntity.ok().build();
    }

    // GET /api/v1/media/songs/jurisdiction/{id} (top songs, page 3)
    @GetMapping("/songs/jurisdiction/{jurisdictionId}")
    public ResponseEntity<List<Song>> getTopSongsByJurisdiction(@PathVariable UUID jurisdictionId, @RequestParam(defaultValue = "3") int limit) {
        List<Song> songs = mediaService.getTopSongsByJurisdiction(jurisdictionId, limit);
        return ResponseEntity.ok(songs);
    }

    // GET /api/v1/media/songs/artist/{artistId} (dashboard media, page 7)
    @GetMapping("/songs/artist/{artistId}")
    public ResponseEntity<List<Song>> getSongsByArtist(@PathVariable UUID artistId) {
        List<Song> songs = mediaService.getSongsByArtist(artistId);
        return ResponseEntity.ok(songs);
    }

    // Mirror for videos (addVideo, deleteVideo, playVideo, getTopVideosByJurisdiction, getVideosByArtist)
}