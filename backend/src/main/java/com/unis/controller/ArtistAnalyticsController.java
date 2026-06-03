package com.unis.controller;

import com.unis.service.ArtistFanbaseService;
import com.unis.util.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Artist-facing analytics endpoints for the Artist Dashboard.
 *
 * NOTE: Renamed from "AnalyticsController" to "ArtistAnalyticsController" and
 * moved to /api/v1/artist-analytics so it does NOT collide with the existing
 * platform/admin analytics controller (which owns /api/v1/analytics).
 *
 * Everything is computed live from existing tables — no migration required,
 * and it returns truthful zeros pre-launch instead of placeholder values.
 */
@RestController
@RequestMapping("/v1/artist-analytics")
public class ArtistAnalyticsController {

    private final ArtistFanbaseService artistFanbaseService;

    public ArtistAnalyticsController(ArtistFanbaseService artistFanbaseService) {
        this.artistFanbaseService = artistFanbaseService;
    }

    /**
     * Fanbase funnel + recent named supporters + 30-day supporter growth.
     * Only the artist may view their own fanbase analytics.
     */
    @GetMapping("/artist/{artistId}/fanbase")
    public ResponseEntity<Map<String, Object>> getArtistFanbase(@PathVariable UUID artistId) {
        UUID requesterId = SecurityUtils.getAuthenticatedUserId();

        if (!requesterId.equals(artistId)) {
            return ResponseEntity.status(403).build();
        }

        return ResponseEntity.ok(artistFanbaseService.getArtistFanbase(artistId));
    }
}