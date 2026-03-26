package com.unis.controller;

import com.unis.entity.Song;
import com.unis.entity.Video;
import com.unis.util.SecurityUtils;
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

    // POST /api/v1/media/song — C1 + C6: now requires auth (SecurityConfig), artistId from JWT
    @PostMapping(value = "/song", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Song> addSong(
            @RequestPart("song") String songJson,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "artwork", required = false) MultipartFile artwork) {
        // C6 FIX: artistId will be extracted from JWT inside MediaService
        // The songJson still contains artistId from the frontend but MediaService should
        // override it with the authenticated user. See note below.
        Song saved = mediaService.addSong(songJson, file, artwork);
        return ResponseEntity.ok(saved);
    }

    // POST /api/v1/media/video
    @PostMapping(value = "/video", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Video> addVideo(
            @RequestPart("video") String videoJson,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "artwork", required = false) MultipartFile artwork) {
        Video saved = mediaService.addVideo(videoJson, file, artwork);
        return ResponseEntity.ok(saved);
    }

    // DELETE /api/v1/media/song/{id}
    @DeleteMapping("/song/{songId}")
    public ResponseEntity<Void> deleteSong(@PathVariable UUID songId) {
        mediaService.deleteSong(songId);
        return ResponseEntity.ok().build();
    }

    // PATCH /api/v1/media/song/{id}
    @PatchMapping(value = "/song/{songId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Song> updateSong(
            @PathVariable UUID songId,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "artwork", required = false) MultipartFile artwork,
            @RequestParam(value = "lyrics", required = false) String lyrics,
            @RequestParam(required = false) String isrc,
            @RequestParam(required = false) UUID cleanVersionId  
)
             {
        try {
            Song updated = mediaService.updateSong(songId, description, artwork, lyrics, isrc, cleanVersionId);
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            log.error("Failed to update song {}: {}", songId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // DELETE /api/v1/media/video/{id}
    @DeleteMapping("/video/{videoId}")
    public ResponseEntity<Void> deleteVideo(@PathVariable UUID videoId) {
        mediaService.deleteVideo(videoId);
        return ResponseEntity.ok().build();
    }

    // POST /api/v1/media/song/{id}/play
    // C6 FIX: Use JWT userId instead of query param
    @PostMapping("/song/{songId}/play")
    public ResponseEntity<Void> playSong(@PathVariable UUID songId,
                                          @RequestParam(required = false) UUID userId) {
        // Use JWT if available, fall back to query param for unauthenticated play tracking
        UUID resolvedUserId;
        try {
            resolvedUserId = SecurityUtils.getAuthenticatedUserId();
        } catch (Exception e) {
            resolvedUserId = userId;
        }
        mediaService.playSong(songId, resolvedUserId);
        return ResponseEntity.ok().build();
    }

    // POST /api/v1/media/video/{id}/play
    @PostMapping("/video/{videoId}/play")
    public ResponseEntity<Void> playVideo(@PathVariable UUID videoId,
                                           @RequestParam(required = false) UUID userId) {
        UUID resolvedUserId;
        try {
            resolvedUserId = SecurityUtils.getAuthenticatedUserId();
        } catch (Exception e) {
            resolvedUserId = userId;
        }
        mediaService.playVideo(videoId, resolvedUserId);
        return ResponseEntity.ok().build();
    }

    // ========== LIKES ENDPOINTS ==========

    // POST /api/v1/media/song/{songId}/like — C6 FIX: userId from JWT
    @PostMapping("/song/{songId}/like")
    public ResponseEntity<Map<String, Object>> likeSong(
            @PathVariable UUID songId,
            @RequestParam(required = false) UUID userId) {
        try {
            // C6 FIX: Use JWT userId
            UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();
            boolean liked = mediaService.likeSong(songId, authenticatedUserId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", liked);
            response.put("message", liked ? "Song liked" : "Already liked");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to like song {}: {}", songId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", "Failed to like song"));
        }
    }

    // DELETE /api/v1/media/song/{songId}/like — C6 FIX: userId from JWT
    @DeleteMapping("/song/{songId}/like")
    public ResponseEntity<Map<String, Object>> unlikeSong(
            @PathVariable UUID songId,
            @RequestParam(required = false) UUID userId) {
        try {
            UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();
            boolean unliked = mediaService.unlikeSong(songId, authenticatedUserId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", unliked);
            response.put("message", unliked ? "Song unliked" : "Like not found");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to unlike song {}: {}", songId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", "Failed to unlike song"));
        }
    }

    // GET /api/v1/media/song/{songId}/is-liked — C6 FIX: userId from JWT
    @GetMapping("/song/{songId}/is-liked")
    public ResponseEntity<Map<String, Boolean>> isLiked(
            @PathVariable UUID songId,
            @RequestParam(required = false) UUID userId) {
        try {
            UUID resolvedUserId;
            try {
                resolvedUserId = SecurityUtils.getAuthenticatedUserId();
            } catch (Exception e) {
                resolvedUserId = userId;
            }
            boolean liked = mediaService.isLiked(songId, resolvedUserId);
            return ResponseEntity.ok(Map.of("isLiked", liked));
        } catch (Exception e) {
            log.error("Failed to check like status {}: {}", songId, e.getMessage());
            return ResponseEntity.ok(Map.of("isLiked", false));
        }
    }

    // GET /api/v1/media/song/{songId}/likes/count
    @GetMapping("/song/{songId}/likes/count")
    public ResponseEntity<Map<String, Integer>> getLikeCount(@PathVariable UUID songId) {
        try {
            int count = mediaService.getLikeCount(songId);
            return ResponseEntity.ok(Map.of("count", count));
        } catch (Exception e) {
            log.error("Failed to get like count {}: {}", songId, e.getMessage());
            return ResponseEntity.ok(Map.of("count", 0));
        }
    }

    // ========== END LIKES ENDPOINTS ==========

    @GetMapping("/songs/jurisdiction/{jurisdictionId}")
    public ResponseEntity<List<Song>> getTopSongsByJurisdiction(
            @PathVariable UUID jurisdictionId,
            @RequestParam(defaultValue = "3") int limit) {
        List<Song> songs = mediaService.getTopSongsByJurisdiction(jurisdictionId, limit);
        return ResponseEntity.ok(songs);
    }

    @GetMapping("/videos/jurisdiction/{jurisdictionId}")
    public ResponseEntity<List<Video>> getTopVideosByJurisdiction(
            @PathVariable UUID jurisdictionId,
            @RequestParam(defaultValue = "3") int limit) {
        List<Video> videos = mediaService.getTopVideosByJurisdiction(jurisdictionId, limit);
        return ResponseEntity.ok(videos);
    }

    @GetMapping("/songs/artist/{artistId}")
    public ResponseEntity<List<Song>> getSongsByArtist(@PathVariable UUID artistId) {
        List<Song> songs = mediaService.getSongsByArtist(artistId);
        return ResponseEntity.ok(songs);
    }

    @GetMapping("/videos/artist/{artistId}")
    public ResponseEntity<List<Video>> getVideosByArtist(@PathVariable UUID artistId) {
        List<Video> videos = mediaService.getVideosByArtist(artistId);
        return ResponseEntity.ok(videos);
    }

    @GetMapping("/song/{songId}")
    public ResponseEntity<Song> getSong(@PathVariable UUID songId) {
        Song song = mediaService.getSongById(songId);
        return ResponseEntity.ok(song);
    }

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

    @GetMapping("/new")
    public ResponseEntity<List<Song>> getNewMedia(
            @RequestParam UUID jurisdictionId,
            @RequestParam(defaultValue = "5") int limit) {
        try {
            List<Song> newSongs = mediaService.getNewSongsByJurisdiction(jurisdictionId, limit);
            return ResponseEntity.ok(newSongs);
        } catch (Exception e) {
            log.error("New media query failed, falling back to trending:", e);
            List<Object> trendingMixed = getTrendingMedia(jurisdictionId, limit).getBody();
            List<Song> fallbackSongs = trendingMixed.stream()
                .filter(o -> o instanceof Song)
                .map(o -> (Song) o)
                .limit(limit)
                .collect(Collectors.toList());
            return ResponseEntity.ok(fallbackSongs);
        }
    }

    @PatchMapping("/song/{songId}/lyrics")
    public ResponseEntity<Song> updateLyrics(
            @PathVariable UUID songId,
            @RequestBody Map<String, String> body) {
        String lyrics = body.get("lyrics");
        Song updated = mediaService.updateSong(songId, null, null, lyrics, null, null);
        return ResponseEntity.ok(updated);
    }
}