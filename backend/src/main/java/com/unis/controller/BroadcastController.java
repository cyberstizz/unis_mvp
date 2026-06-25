package com.unis.controller;

import com.unis.service.ArtistBroadcastService;
import com.unis.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Artist broadcast endpoint. Kept in its own controller so the existing
 * MessageController stays untouched.
 *
 * POST /api/v1/messages/broadcast   Body: { "body": "..." }
 */
@RestController
@RequestMapping("/api/v1/messages")
@Slf4j
public class BroadcastController {

    private final ArtistBroadcastService artistBroadcastService;

    public BroadcastController(ArtistBroadcastService artistBroadcastService) {
        this.artistBroadcastService = artistBroadcastService;
    }

    @PostMapping("/broadcast")
    public ResponseEntity<?> broadcast(@RequestBody Map<String, String> body) {
        try {
            UUID artistId = SecurityUtils.getAuthenticatedUserId();
            Map<String, Object> result = artistBroadcastService.broadcastToSupporters(artistId, body.get("body"));
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}