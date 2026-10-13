package com.unis.controller;

import com.unis.dto.SongDiscoveryRequest;
import com.unis.entity.Song;
import com.unis.service.DiscoveryService;
import com.unis.service.MediaService;
import com.unis.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Subgenres, moods and language: the lists the upload form offers, Discover's
 * subgenre chips, the browse shelves, the song page's recommended carousel,
 * and editing a song's tags after upload.
 *
 * Reads are public (guests can browse). The PATCH is limited to the song's
 * artist or an admin; SecurityConfig already requires login for PATCH
 * /api/v1/media/song/**.
 */
@Slf4j
@RestController
public class DiscoveryController {

    @Autowired private DiscoveryService discoveryService;
    @Autowired private MediaService mediaService;

    private static boolean isAdmin() {
        org.springframework.security.core.Authentication auth =
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    // GET /api/v1/taxonomy — {subgenres, moods, languages}
    @GetMapping("/api/v1/taxonomy")
    public ResponseEntity<Map<String, Object>> taxonomy() {
        return ResponseEntity.ok(discoveryService.getTaxonomy());
    }

    // GET /api/v1/taxonomy/counts?jurisdictionId=&min=3 — subgenres with enough songs for a chip
    @GetMapping("/api/v1/taxonomy/counts")
    public ResponseEntity<List<Map<String, Object>>> counts(
            @RequestParam(required = false) UUID jurisdictionId,
            @RequestParam(defaultValue = "3") int min) {
        return ResponseEntity.ok(discoveryService.getSubgenreCounts(jurisdictionId, min));
    }

    // GET /api/v1/media/songs/browse?subgenre=drill  or  ?mood=chill  (&jurisdictionId=&limit=)
    @GetMapping("/api/v1/media/songs/browse")
    public ResponseEntity<?> browse(
            @RequestParam(required = false) String subgenre,
            @RequestParam(required = false) String mood,
            @RequestParam(required = false) UUID jurisdictionId,
            @RequestParam(defaultValue = "50") int limit) {
        try {
            return ResponseEntity.ok(discoveryService.browse(subgenre, mood, jurisdictionId, limit));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", String.valueOf(e.getReason())));
        }
    }

    // GET /api/v1/media/song/{songId}/recommended?limit=8
    @GetMapping("/api/v1/media/song/{songId}/recommended")
    public ResponseEntity<?> recommended(
            @PathVariable UUID songId,
            @RequestParam(defaultValue = "8") int limit) {
        try {
            return ResponseEntity.ok(discoveryService.recommended(songId, limit));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", String.valueOf(e.getReason())));
        }
    }

    // PATCH /api/v1/media/song/{songId}/discovery — JSON body, artist or admin only
    @PatchMapping("/api/v1/media/song/{songId}/discovery")
    public ResponseEntity<?> updateDiscovery(
            @PathVariable UUID songId,
            @RequestBody SongDiscoveryRequest body) {
        try {
            UUID requesterId = SecurityUtils.getAuthenticatedUserId();
            Song updated = mediaService.updateDiscovery(songId, requesterId, isAdmin(), body);
            return ResponseEntity.ok(updated);
        } catch (MediaService.NotOwnerException e) {
            log.warn("[Discovery] refused songId={} reason=not_owner", songId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", String.valueOf(e.getReason())));
        }
    }
}
