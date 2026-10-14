package com.unis.controller;

import com.unis.entity.Song;
import com.unis.entity.Video;
import com.unis.service.SignupUploadService;
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

    @Autowired 
    private SignupUploadService signupUploadService; 

    @Autowired
    private com.unis.service.AnonymousPlayService anonymousPlayService;

    // POST /api/v1/media/signup-song — debut upload during signup (token-authorized, no login)
    @PostMapping(value = "/signup-song", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Song> addSignupSong(
            @RequestParam("signupToken") String signupToken,
            @RequestPart("song") String songJson,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "artwork", required = false) MultipartFile artwork) {
        UUID artistId = signupUploadService.consume(signupToken);   // validates + single-use
        Song saved = mediaService.addSongForArtist(artistId, songJson, file, artwork);
        return ResponseEntity.ok(saved);
    }

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

    /** True if the logged-in user holds the admin role (set by UserDetailsServiceImpl). */
    private static boolean isAdmin() {
        org.springframework.security.core.Authentication auth =
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    // DELETE /api/v1/media/song/{id}
    // Removes the song (it stays in award history; see MediaService.removeSong).
    // Only the song's artist or an admin — previously any logged-in user could
    // delete anyone's song.
    @DeleteMapping("/song/{songId}")
    public ResponseEntity<?> deleteSong(@PathVariable UUID songId) {
        try {
            UUID requesterId = SecurityUtils.getAuthenticatedUserId();
            mediaService.removeSongAsOwner(songId, requesterId, isAdmin());
            log.info("[SongRemove] songId={} requester={}", songId, requesterId);
            return ResponseEntity.ok().build();
        } catch (MediaService.NotOwnerException e) {
            log.warn("[SongRemove] refused songId={} reason=not_owner", songId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", String.valueOf(e.getReason())));
        }
    }

    // PATCH /api/v1/media/song/{id} — only the song's artist or an admin
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
            UUID requesterId = SecurityUtils.getAuthenticatedUserId();
            Song updated = mediaService.updateSong(songId, requesterId, isAdmin(), description, artwork, lyrics, isrc, cleanVersionId);
            return ResponseEntity.ok(updated);
        } catch (MediaService.NotOwnerException e) {
            log.warn("Refused update of song {}: not the owner", songId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).build();
        } catch (Exception e) {
            log.error("Failed to update song {}: {}", songId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // DELETE /api/v1/media/video/{id} — only the video's artist or an admin
    @DeleteMapping("/video/{videoId}")
    public ResponseEntity<?> deleteVideo(@PathVariable UUID videoId) {
        try {
            UUID requesterId = SecurityUtils.getAuthenticatedUserId();
            mediaService.deleteVideoAsOwner(videoId, requesterId, isAdmin());
            return ResponseEntity.ok().build();
        } catch (MediaService.NotOwnerException e) {
            log.warn("[VideoDelete] refused videoId={} reason=not_owner", videoId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", String.valueOf(e.getReason())));
        }
    }

    // POST /api/v1/media/song/{id}/play
    @PostMapping("/song/{songId}/play")
    public ResponseEntity<Map<String, Object>> playSong(
            @PathVariable UUID songId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String source) {
        // Only a logged-in listener's play counts here. The userId in the URL
        // is no longer trusted on its own: before, a request with no login could
        // credit plays (and points) to any account by naming it. Logged-out
        // listening goes to /song/{id}/anonymous-play instead.
        UUID resolvedUserId;
        try { resolvedUserId = SecurityUtils.getAuthenticatedUserId(); }
        catch (Exception e) { resolvedUserId = null; }

        UUID playId = mediaService.playSong(songId, resolvedUserId, source);
        Map<String, Object> body = new HashMap<>();
        body.put("playId", playId != null ? playId.toString() : null);
        body.put("counted", playId != null);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/play/complete")
    public ResponseEntity<Void> completePlay(@RequestBody Map<String, Object> body) {
        Object playIdObj = body.get("playId");
        if (playIdObj == null) return ResponseEntity.badRequest().build();
        double pct = body.get("percentPlayed") != null
            ? Double.parseDouble(body.get("percentPlayed").toString()) : 0.0;
        mediaService.completeSongPlay(UUID.fromString(playIdObj.toString()), pct);
        return ResponseEntity.ok().build();
    }

    // POST /api/v1/media/song/{id}/anonymous-play?visitorId=&source=
    // A play by someone who isn't logged in. Kept apart from counted plays:
    // no points, no play count, no awards. See AnonymousPlayService.
    @PostMapping("/song/{songId}/anonymous-play")
    public ResponseEntity<Map<String, Object>> anonymousPlay(
            @PathVariable UUID songId,
            @RequestParam UUID visitorId,
            @RequestParam(required = false) String source) {
        UUID playId = anonymousPlayService.record(songId, visitorId, source);
        Map<String, Object> body = new HashMap<>();
        body.put("playId", playId != null ? playId.toString() : null);
        body.put("counted", playId != null);
        return ResponseEntity.ok(body);
    }

    // POST /api/v1/media/anonymous-play/complete  {playId, visitorId, percentPlayed}
    @PostMapping("/anonymous-play/complete")
    public ResponseEntity<Void> completeAnonymousPlay(@RequestBody Map<String, Object> body) {
        try {
            UUID playId = UUID.fromString(String.valueOf(body.get("playId")));
            UUID visitorId = UUID.fromString(String.valueOf(body.get("visitorId")));
            double pct = body.get("percentPlayed") != null
                ? Double.parseDouble(body.get("percentPlayed").toString()) : 0.0;
            anonymousPlayService.complete(playId, visitorId, pct);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // POST /api/v1/media/video/{id}/play
    @PostMapping("/video/{videoId}/play")
    public ResponseEntity<Void> playVideo(@PathVariable UUID videoId,
                                           @RequestParam(required = false) UUID userId) {
        // Same rule as song plays: only a logged-in viewer's play counts; the
        // userId in the URL is not trusted on its own.
        UUID resolvedUserId;
        try {
            resolvedUserId = SecurityUtils.getAuthenticatedUserId();
        } catch (Exception e) {
            return ResponseEntity.ok().build();
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
            MediaService.LikeOutcome outcome = mediaService.likeSong(songId, authenticatedUserId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", outcome.liked());
            response.put("message", outcome.liked() ? "Song liked" : "Already liked");
            // 1 the first time this person likes this track, 0 on a re-like.
            response.put("pointsAwarded", outcome.pointsAwarded());
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

    // ---------- VIDEO LIKES — same shapes as the song endpoints ----------

    // POST /api/v1/media/video/{videoId}/like — userId from JWT
    @PostMapping("/video/{videoId}/like")
    public ResponseEntity<Map<String, Object>> likeVideo(
            @PathVariable UUID videoId,
            @RequestParam(required = false) UUID userId) {
        try {
            UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();
            MediaService.LikeOutcome outcome = mediaService.likeVideo(videoId, authenticatedUserId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", outcome.liked());
            response.put("message", outcome.liked() ? "Video liked" : "Already liked");
            response.put("pointsAwarded", outcome.pointsAwarded());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to like video {}: {}", videoId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", "Failed to like video"));
        }
    }

    // DELETE /api/v1/media/video/{videoId}/like — userId from JWT
    @DeleteMapping("/video/{videoId}/like")
    public ResponseEntity<Map<String, Object>> unlikeVideo(
            @PathVariable UUID videoId,
            @RequestParam(required = false) UUID userId) {
        try {
            UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();
            boolean unliked = mediaService.unlikeVideo(videoId, authenticatedUserId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", unliked);
            response.put("message", unliked ? "Video unliked" : "Like not found");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to unlike video {}: {}", videoId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", "Failed to unlike video"));
        }
    }

    // GET /api/v1/media/video/{videoId}/is-liked — userId from JWT, guest-safe
    @GetMapping("/video/{videoId}/is-liked")
    public ResponseEntity<Map<String, Boolean>> isVideoLiked(
            @PathVariable UUID videoId,
            @RequestParam(required = false) UUID userId) {
        try {
            UUID resolvedUserId;
            try {
                resolvedUserId = SecurityUtils.getAuthenticatedUserId();
            } catch (Exception e) {
                resolvedUserId = userId;
            }
            boolean liked = mediaService.isVideoLiked(videoId, resolvedUserId);
            return ResponseEntity.ok(Map.of("isLiked", liked));
        } catch (Exception e) {
            log.error("Failed to check video like status {}: {}", videoId, e.getMessage());
            return ResponseEntity.ok(Map.of("isLiked", false));
        }
    }

    // GET /api/v1/media/video/{videoId}/likes/count
    @GetMapping("/video/{videoId}/likes/count")
    public ResponseEntity<Map<String, Integer>> getVideoLikeCount(@PathVariable UUID videoId) {
        try {
            int count = mediaService.getVideoLikeCount(videoId);
            return ResponseEntity.ok(Map.of("count", count));
        } catch (Exception e) {
            log.error("Failed to get video like count {}: {}", videoId, e.getMessage());
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

    // GET /api/v1/media/videos/recent?limit=12 — newest videos, any jurisdiction
    @GetMapping("/videos/recent")
    public ResponseEntity<List<Video>> getRecentVideos(
            @RequestParam(defaultValue = "12") int limit) {
        return ResponseEntity.ok(mediaService.getRecentVideos(limit));
    }

    @GetMapping("/videos/artist/{artistId}")
    public ResponseEntity<List<Video>> getVideosByArtist(@PathVariable UUID artistId) {
        List<Video> videos = mediaService.getVideosByArtist(artistId);
        return ResponseEntity.ok(videos);
    }

    // GET /api/v1/media/video/{id} — single video with decorated stats
    @GetMapping("/video/{videoId}")
    public ResponseEntity<Video> getVideo(@PathVariable UUID videoId) {
        Video video = mediaService.getVideoById(videoId);
        return ResponseEntity.ok(video);
    }

    // A removed song still answers 200 with "removed": true (title and artist,
    // no audio or artwork), so its page can say it was removed. A song that
    // never existed is a 404.
    @GetMapping("/song/{songId}")
    public ResponseEntity<Song> getSong(@PathVariable UUID songId) {
        try {
            Song song = mediaService.getSongById(songId);
            return ResponseEntity.ok(song);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/song/{songId}/lyrics")
    public ResponseEntity<Map<String, Object>> getSongLyrics(@PathVariable UUID songId) {
        try {
            Song song = mediaService.getSongById(songId);
            if (song.isRemoved()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
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

    // Only the song's artist or an admin (previously any logged-in user).
    @PatchMapping("/song/{songId}/lyrics")
    public ResponseEntity<Song> updateLyrics(
            @PathVariable UUID songId,
            @RequestBody Map<String, String> body) {
        String lyrics = body.get("lyrics");
        try {
            UUID requesterId = SecurityUtils.getAuthenticatedUserId();
            Song updated = mediaService.updateSong(songId, requesterId, isAdmin(), null, null, lyrics, null, null);
            return ResponseEntity.ok(updated);
        } catch (MediaService.NotOwnerException e) {
            log.warn("Refused lyrics update of song {}: not the owner", songId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).build();
        }
    }
}