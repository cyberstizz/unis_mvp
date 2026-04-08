package com.unis.controller;

import com.unis.dto.PlaylistDtos.*;
import com.unis.service.FileStorageService;
import com.unis.service.PlaylistService;
import com.unis.util.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/playlists")
public class PlaylistController {

    private final PlaylistService playlistService;
    private final FileStorageService fileStorageService;

    public PlaylistController(PlaylistService playlistService, FileStorageService fileStorageService) {
        this.playlistService = playlistService;
        this.fileStorageService = fileStorageService;
    }

    // ========================================================================
    // PERSONAL PLAYLIST CRUD
    // ========================================================================

    @GetMapping("/mine")
    public ResponseEntity<List<PlaylistSummaryResponse>> myPlaylists() {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.getMyPlaylists(userId));
    }

    @PostMapping
    public ResponseEntity<PlaylistResponse> createPlaylist(@RequestBody CreatePlaylistRequest req) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.createPlaylist(userId, req));
    }

    @GetMapping("/{playlistId}")
    public ResponseEntity<PlaylistResponse> getPlaylist(@PathVariable UUID playlistId) {
        UUID viewerUserId = null;
        try {
            viewerUserId = SecurityUtils.getAuthenticatedUserId();
        } catch (Exception ignored) {}
        return ResponseEntity.ok(playlistService.getPlaylistById(playlistId, viewerUserId));
    }

    @PutMapping("/{playlistId}")
    public ResponseEntity<PlaylistResponse> updatePlaylist(
            @PathVariable UUID playlistId,
            @RequestBody UpdatePlaylistRequest req) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.updatePlaylist(playlistId, userId, req));
    }

    @DeleteMapping("/{playlistId}")
    public ResponseEntity<Void> deletePlaylist(@PathVariable UUID playlistId) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        playlistService.deletePlaylist(playlistId, userId);
        return ResponseEntity.noContent().build();
    }

    // ========================================================================
    // COVER IMAGE UPLOAD
    // ========================================================================

    /**
     * Upload a cover image and return the URL.
     * Frontend uploads first, gets the URL, then includes it in
     * createPlaylist or updatePlaylist.
     */
    @PostMapping("/cover")
    public ResponseEntity<Map<String, String>> uploadCover(
            @RequestParam("cover") MultipartFile file) throws IOException {
        // Authenticated users only — security rule added in SecurityConfig
        SecurityUtils.getAuthenticatedUserId();

        String coverUrl = fileStorageService.storeFile(file);
        return ResponseEntity.ok(Map.of("coverImageUrl", coverUrl));
    }

    /**
     * Update an existing playlist's cover image directly.
     * Owner-only — checked in PlaylistService.updatePlaylist.
     */
    @PostMapping("/{playlistId}/cover")
    public ResponseEntity<PlaylistResponse> updatePlaylistCover(
            @PathVariable UUID playlistId,
            @RequestParam("cover") MultipartFile file) throws IOException {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        String coverUrl = fileStorageService.storeFile(file);

        UpdatePlaylistRequest req = new UpdatePlaylistRequest();
        req.setCoverImageUrl(coverUrl);
        return ResponseEntity.ok(playlistService.updatePlaylist(playlistId, userId, req));
    }

    // ========================================================================
    // TRACK MANAGEMENT
    // ========================================================================

    @PostMapping("/{playlistId}/tracks")
    public ResponseEntity<PlaylistResponse> addTrack(
            @PathVariable UUID playlistId,
            @RequestBody AddTrackRequest req) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.addTrack(playlistId, userId, req.getSongId()));
    }

    @DeleteMapping("/{playlistId}/tracks/{itemId}")
    public ResponseEntity<PlaylistResponse> removeTrack(
            @PathVariable UUID playlistId,
            @PathVariable UUID itemId) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.removeTrack(playlistId, userId, itemId));
    }

    @PutMapping("/{playlistId}/reorder")
    public ResponseEntity<PlaylistResponse> reorderTracks(
            @PathVariable UUID playlistId,
            @RequestBody List<UUID> orderedItemIds) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.reorderTracks(playlistId, userId, orderedItemIds));
    }

    // ========================================================================
    // COMMUNITY PLAYLIST ENDPOINTS
    // ========================================================================

    @PostMapping("/{playlistId}/suggest")
    public ResponseEntity<TrackResponse> suggestSong(
            @PathVariable UUID playlistId,
            @RequestBody AddTrackRequest req) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.suggestSong(playlistId, userId, req.getSongId()));
    }

    @PostMapping("/{playlistId}/tracks/{itemId}/vote")
    public ResponseEntity<TrackResponse> voteOnSuggestion(
            @PathVariable UUID playlistId,
            @PathVariable UUID itemId,
            @RequestBody PlaylistVoteRequest req) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.voteOnSuggestion(itemId, userId, req.getVoteType()));
    }

    @DeleteMapping("/{playlistId}/tracks/{itemId}/curator-remove")
    public ResponseEntity<Void> curatorRemoveSong(
            @PathVariable UUID playlistId,
            @PathVariable UUID itemId,
            @RequestParam(required = false) String reason) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        playlistService.curatorRemoveSong(playlistId, userId, itemId, reason);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{playlistId}/pending")
    public ResponseEntity<List<TrackResponse>> getPendingSuggestions(@PathVariable UUID playlistId) {
        UUID viewerUserId = null;
        try { viewerUserId = SecurityUtils.getAuthenticatedUserId(); } catch (Exception ignored) {}
        return ResponseEntity.ok(playlistService.getPendingSuggestions(playlistId, viewerUserId));
    }

    @GetMapping("/{playlistId}/activity")
    public ResponseEntity<List<ActivityResponse>> getActivity(
            @PathVariable UUID playlistId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID viewerUserId = null;
        try { viewerUserId = SecurityUtils.getAuthenticatedUserId(); } catch (Exception ignored) {}
        return ResponseEntity.ok(playlistService.getPlaylistActivity(playlistId, viewerUserId, page, size));
    }

    // ========================================================================
    // FOLLOW
    // ========================================================================

    @PostMapping("/{playlistId}/follow")
    public ResponseEntity<Void> followPlaylist(@PathVariable UUID playlistId) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        playlistService.followPlaylist(userId, playlistId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{playlistId}/follow")
    public ResponseEntity<Void> unfollowPlaylist(@PathVariable UUID playlistId) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        playlistService.unfollowPlaylist(userId, playlistId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/following")
    public ResponseEntity<List<PlaylistSummaryResponse>> getFollowedPlaylists() {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.getFollowedPlaylists(userId));
    }

    // ========================================================================
    // DISCOVERY
    // ========================================================================

    @GetMapping("/discover")
    public ResponseEntity<List<PlaylistSummaryResponse>> discoverPlaylists(
            @RequestParam(required = false) UUID jurisdictionId) {
        return ResponseEntity.ok(playlistService.discoverPlaylists(jurisdictionId));
    }

    @GetMapping("/community/{jurisdictionId}")
    public ResponseEntity<List<PlaylistSummaryResponse>> getCommunityPlaylists(
            @PathVariable UUID jurisdictionId) {
        return ResponseEntity.ok(playlistService.getCommunityPlaylists(jurisdictionId));
    }

    @GetMapping("/official")
    public ResponseEntity<List<PlaylistSummaryResponse>> getOfficialPlaylists() {
        return ResponseEntity.ok(playlistService.getOfficialPlaylists());
    }

    @GetMapping("/search")
    public ResponseEntity<List<PlaylistSummaryResponse>> searchPlaylists(
            @RequestParam String q) {
        return ResponseEntity.ok(playlistService.searchPlaylists(q));
    }

    // ========================================================================
    // BLOCKED SONGS
    // ========================================================================

    @PostMapping("/blocked-songs")
    public ResponseEntity<Void> blockSong(@RequestBody Map<String, UUID> body) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        UUID songId = body.get("songId");
        if (songId == null) return ResponseEntity.badRequest().build();
        playlistService.blockSong(userId, songId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/blocked-songs/{songId}")
    public ResponseEntity<Void> unblockSong(@PathVariable UUID songId) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        playlistService.unblockSong(userId, songId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/blocked-songs")
    public ResponseEntity<List<BlockedSongResponse>> getBlockedSongs() {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.getBlockedSongs(userId));
    }
}