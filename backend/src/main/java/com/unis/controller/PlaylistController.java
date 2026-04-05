package com.unis.controller;

import com.unis.dto.PlaylistDtos.*;
import com.unis.service.PlaylistService;
import com.unis.util.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/playlists")
public class PlaylistController {

    private final PlaylistService playlistService;

    public PlaylistController(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    // ========================================================================
    // PERSONAL PLAYLIST CRUD
    // ========================================================================

    /** Get all playlists owned by the authenticated user */
    @GetMapping("/mine")
    public ResponseEntity<List<PlaylistSummaryResponse>> myPlaylists() {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.getMyPlaylists(userId));
    }

    /** Create a new playlist (personal, community, or official) */
    @PostMapping
    public ResponseEntity<PlaylistResponse> createPlaylist(@RequestBody CreatePlaylistRequest req) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.createPlaylist(userId, req));
    }

    /** Get a single playlist with tracks (visibility-gated) */
    @GetMapping("/{playlistId}")
    public ResponseEntity<PlaylistResponse> getPlaylist(@PathVariable UUID playlistId) {
        // Try to get authenticated user, but allow anonymous for public playlists
        UUID viewerUserId = null;
        try {
            viewerUserId = SecurityUtils.getAuthenticatedUserId();
        } catch (Exception ignored) {
            // Anonymous viewer — will only see public/unlisted playlists
        }
        return ResponseEntity.ok(playlistService.getPlaylistById(playlistId, viewerUserId));
    }

    /** Update playlist metadata (name, visibility, description, cover) */
    @PutMapping("/{playlistId}")
    public ResponseEntity<PlaylistResponse> updatePlaylist(
            @PathVariable UUID playlistId,
            @RequestBody UpdatePlaylistRequest req) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.updatePlaylist(playlistId, userId, req));
    }

    /** Soft-delete a playlist (owner only) */
    @DeleteMapping("/{playlistId}")
    public ResponseEntity<Void> deletePlaylist(@PathVariable UUID playlistId) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        playlistService.deletePlaylist(playlistId, userId);
        return ResponseEntity.noContent().build();
    }

    /** Add a track to a personal playlist (owner only) */
    @PostMapping("/{playlistId}/tracks")
    public ResponseEntity<PlaylistResponse> addTrack(
            @PathVariable UUID playlistId,
            @RequestBody AddTrackRequest req) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.addTrack(playlistId, userId, req.getSongId()));
    }

    /** Remove a track from a playlist (owner only) */
    @DeleteMapping("/{playlistId}/tracks/{itemId}")
    public ResponseEntity<PlaylistResponse> removeTrack(
            @PathVariable UUID playlistId,
            @PathVariable UUID itemId) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.removeTrack(playlistId, userId, itemId));
    }

    /** Reorder tracks in a playlist (owner only) */
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

    /** Suggest a song for a community playlist */
    @PostMapping("/{playlistId}/suggest")
    public ResponseEntity<TrackResponse> suggestSong(
            @PathVariable UUID playlistId,
            @RequestBody AddTrackRequest req) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.suggestSong(playlistId, userId, req.getSongId()));
    }

    /** Vote on a pending suggestion in a community playlist */
    @PostMapping("/{playlistId}/tracks/{itemId}/vote")
    public ResponseEntity<TrackResponse> voteOnSuggestion(
            @PathVariable UUID playlistId,
            @PathVariable UUID itemId,
            @RequestBody PlaylistVoteRequest req) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.voteOnSuggestion(itemId, userId, req.getVoteType()));
    }

    /** Curator removes a song from a community playlist */
    @DeleteMapping("/{playlistId}/tracks/{itemId}/curator-remove")
    public ResponseEntity<Void> curatorRemoveSong(
            @PathVariable UUID playlistId,
            @PathVariable UUID itemId,
            @RequestParam(required = false) String reason) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        playlistService.curatorRemoveSong(playlistId, userId, itemId, reason);
        return ResponseEntity.noContent().build();
    }

    /** Get pending song suggestions for a community playlist */
    @GetMapping("/{playlistId}/pending")
    public ResponseEntity<List<TrackResponse>> getPendingSuggestions(@PathVariable UUID playlistId) {
        UUID viewerUserId = null;
        try {
            viewerUserId = SecurityUtils.getAuthenticatedUserId();
        } catch (Exception ignored) {}
        return ResponseEntity.ok(playlistService.getPendingSuggestions(playlistId, viewerUserId));
    }

    /** Get activity feed for a community playlist */
    @GetMapping("/{playlistId}/activity")
    public ResponseEntity<List<ActivityResponse>> getActivity(
            @PathVariable UUID playlistId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID viewerUserId = null;
        try {
            viewerUserId = SecurityUtils.getAuthenticatedUserId();
        } catch (Exception ignored) {}
        return ResponseEntity.ok(playlistService.getPlaylistActivity(playlistId, viewerUserId, page, size));
    }

    // ========================================================================
    // FOLLOW / UNFOLLOW
    // ========================================================================

    /** Follow a public or unlisted playlist */
    @PostMapping("/{playlistId}/follow")
    public ResponseEntity<Void> followPlaylist(@PathVariable UUID playlistId) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        playlistService.followPlaylist(userId, playlistId);
        return ResponseEntity.ok().build();
    }

    /** Unfollow a playlist */
    @DeleteMapping("/{playlistId}/follow")
    public ResponseEntity<Void> unfollowPlaylist(@PathVariable UUID playlistId) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        playlistService.unfollowPlaylist(userId, playlistId);
        return ResponseEntity.noContent().build();
    }

    /** Get playlists the authenticated user is following */
    @GetMapping("/following")
    public ResponseEntity<List<PlaylistSummaryResponse>> getFollowedPlaylists() {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.getFollowedPlaylists(userId));
    }

    // ========================================================================
    // DISCOVERY (PUBLIC)
    // ========================================================================

    /** Browse public playlists, optionally filtered by jurisdiction */
    @GetMapping("/discover")
    public ResponseEntity<List<PlaylistSummaryResponse>> discoverPlaylists(
            @RequestParam(required = false) UUID jurisdictionId) {
        return ResponseEntity.ok(playlistService.discoverPlaylists(jurisdictionId));
    }

    /** Get community playlists for a jurisdiction */
    @GetMapping("/community/{jurisdictionId}")
    public ResponseEntity<List<PlaylistSummaryResponse>> getCommunityPlaylists(
            @PathVariable UUID jurisdictionId) {
        return ResponseEntity.ok(playlistService.getCommunityPlaylists(jurisdictionId));
    }

    /** Get all official (admin-curated / award-driven) playlists */
    @GetMapping("/official")
    public ResponseEntity<List<PlaylistSummaryResponse>> getOfficialPlaylists() {
        return ResponseEntity.ok(playlistService.getOfficialPlaylists());
    }

    /** Search public playlists by name */
    @GetMapping("/search")
    public ResponseEntity<List<PlaylistSummaryResponse>> searchPlaylists(
            @RequestParam String q) {
        return ResponseEntity.ok(playlistService.searchPlaylists(q));
    }

    // ========================================================================
    // BLOCKED SONGS
    // ========================================================================

    /** Block a song from appearing in recommendations/autoplay */
    @PostMapping("/blocked-songs")
    public ResponseEntity<Void> blockSong(@RequestBody Map<String, UUID> body) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        UUID songId = body.get("songId");
        if (songId == null) {
            return ResponseEntity.badRequest().build();
        }
        playlistService.blockSong(userId, songId);
        return ResponseEntity.ok().build();
    }

    /** Unblock a song */
    @DeleteMapping("/blocked-songs/{songId}")
    public ResponseEntity<Void> unblockSong(@PathVariable UUID songId) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        playlistService.unblockSong(userId, songId);
        return ResponseEntity.noContent().build();
    }

    /** Get all blocked songs for the authenticated user */
    @GetMapping("/blocked-songs")
    public ResponseEntity<List<BlockedSongResponse>> getBlockedSongs() {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        return ResponseEntity.ok(playlistService.getBlockedSongs(userId));
    }
}