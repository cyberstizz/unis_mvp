package com.unis.controller;

import com.unis.service.ArtistFanbaseService;
import com.unis.util.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/artist-analytics")
public class ArtistAnalyticsController {

    private final ArtistFanbaseService artistFanbaseService;

    public ArtistAnalyticsController(ArtistFanbaseService artistFanbaseService) {
        this.artistFanbaseService = artistFanbaseService;
    }

    /**
     * Fanbase funnel + recent named supporters + 30-day supporter growth.
     * Only the artist may view their own fanbase analytics.
     *
     * ★ period: optional query param (today|week|month|year|all). Defaults to
     * "all" for backward compatibility with the original single-snapshot call.
     */
    @GetMapping("/artist/{artistId}/fanbase")
    public ResponseEntity<Map<String, Object>> getArtistFanbase(
            @PathVariable UUID artistId,
            @RequestParam(name = "period", required = false, defaultValue = "all") String period) {

        UUID requesterId = SecurityUtils.getAuthenticatedUserId();

        if (!requesterId.equals(artistId)) {
            return ResponseEntity.status(403).build();
        }

        return ResponseEntity.ok(artistFanbaseService.getArtistFanbase(artistId, period));
    }
}