package com.unis.controller;

import com.unis.dto.AddTrackRequest;
import com.unis.dto.CreatePlaylistRequest;
import com.unis.dto.PlaylistDto;
import com.unis.entity.Playlist;
import com.unis.entity.User;
import com.unis.service.PlaylistService;
import com.unis.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/playlists")
public class PlaylistController {
    private final PlaylistService playlistService;
    private final UserRepository userRepository;

    public PlaylistController(PlaylistService playlistService, UserRepository userRepository) {
        this.playlistService = playlistService;
        this.userRepository = userRepository;
    }

    private User currentUser(Authentication auth) {
        if (auth == null) {
            throw new RuntimeException("Not authenticated");
        }
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping
    public ResponseEntity<List<PlaylistDto>> myPlaylists(Authentication auth) {
        User user = currentUser(auth);
        List<Playlist> list = playlistService.getPlaylistsForUser(user);
        List<PlaylistDto> dtos = list.stream()
                .map(PlaylistDto::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @PostMapping
    public ResponseEntity<PlaylistDto> createPlaylist(@RequestBody CreatePlaylistRequest req, Authentication auth) {
        User user = currentUser(auth);
        Playlist p = playlistService.createPlaylist(user, req.getName());
        return ResponseEntity.ok(PlaylistDto.fromEntity(p));
    }

    @PostMapping("/{playlistId}/tracks")
    public ResponseEntity<PlaylistDto> addTrack(
            @PathVariable UUID playlistId, 
            @RequestBody AddTrackRequest req,
            Authentication auth) {
        User user = currentUser(auth);
        Playlist p = playlistService.addSongToPlaylist(playlistId, req.getSongId(), user);
        return ResponseEntity.ok(PlaylistDto.fromEntity(p));
    }

    @DeleteMapping("/{playlistId}/tracks/{playlistItemId}")
    public ResponseEntity<PlaylistDto> removeTrack(
            @PathVariable UUID playlistId, 
            @PathVariable UUID playlistItemId,
            Authentication auth) {
        User user = currentUser(auth);
        Playlist p = playlistService.removeSongFromPlaylist(playlistId, playlistItemId, user);
        return ResponseEntity.ok(PlaylistDto.fromEntity(p));
    }

    @DeleteMapping("/{playlistId}")
    public ResponseEntity<Void> deletePlaylist(@PathVariable UUID playlistId, Authentication auth) {
        User user = currentUser(auth);
        playlistService.deletePlaylist(playlistId, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{playlistId}")
    public ResponseEntity<PlaylistDto> getPlaylist(@PathVariable UUID playlistId, Authentication auth) {
        User user = currentUser(auth);
        return playlistService.getPlaylist(playlistId, user)
                .map(PlaylistDto::fromEntity)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{playlistId}/reorder")
    public ResponseEntity<PlaylistDto> reorder(
            @PathVariable UUID playlistId, 
            @RequestBody List<UUID> orderedTrackIds,
            Authentication auth) {
        User user = currentUser(auth);
        Playlist p = playlistService.reorderPlaylist(playlistId, orderedTrackIds, user);
        return ResponseEntity.ok(PlaylistDto.fromEntity(p));
    }

    @PutMapping("/{playlistId}")
    public ResponseEntity<PlaylistDto> updatePlaylist(
            @PathVariable UUID playlistId,
            @RequestBody CreatePlaylistRequest req,
            Authentication auth) {
        User user = currentUser(auth);
        Playlist p = playlistService.updatePlaylistName(playlistId, req.getName(), user);
        return ResponseEntity.ok(PlaylistDto.fromEntity(p));
    }
}