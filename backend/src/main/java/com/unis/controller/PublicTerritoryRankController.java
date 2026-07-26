package com.unis.controller;

import com.unis.service.TerritoryRankService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Public, read-only territory rank for an artist's profile page.
 *
 * <p><b>GET /api/v1/users/{artistId}/territory-rank</b>
 *
 * <p>Why this exists: the dashboard's endpoint
 * {@code GET /api/v1/artist-analytics/artist/{artistId}/territory-rank} is
 * deliberately <i>self-only</i> —
 *
 * <pre>
 *   UUID requesterId = SecurityUtils.getAuthenticatedUserId();
 *   if (!requesterId.equals(artistId)) return ResponseEntity.status(403).build();
 * </pre>
 *
 * so a fan viewing someone else's artist page gets 403 when signed in, and 401
 * when signed out (that path is {@code authenticated()} in SecurityConfig).
 * Loosening that guard is the wrong fix: the rest of /artist-analytics/**
 * carries private earnings and demographics data and must stay locked down.
 *
 * <p>This controller instead exposes only the ranking payload, which is
 * public-facing prestige information — the same standing already implied by
 * leaderboards and the awards rails. It delegates to the identical service
 * method, so fans see exactly what the artist sees on their dashboard, with no
 * duplicated ranking logic and nothing private crossing the boundary.
 *
 * <p>Remember to permit it in SecurityConfig (see SECURITY_CONFIG_PATCH.md):
 * <pre>
 *   .requestMatchers(HttpMethod.GET, "/api/v1/users/*&#47;territory-rank").permitAll()
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/users")
public class PublicTerritoryRankController {

    private static final Logger log = LoggerFactory.getLogger(PublicTerritoryRankController.class);

    private final TerritoryRankService territoryRankService;

    public PublicTerritoryRankController(TerritoryRankService territoryRankService) {
        this.territoryRankService = territoryRankService;
    }

    @GetMapping("/{artistId}/territory-rank")
    public ResponseEntity<Map<String, Object>> getPublicTerritoryRank(@PathVariable UUID artistId) {
        try {
            Map<String, Object> payload = territoryRankService.getTerritoryRank(artistId);
            log.info("[TerritoryRank] action=publicRead status=ok artistId={} statusField={}",
                    artistId, payload == null ? null : payload.get("status"));
            return ResponseEntity.ok(payload == null ? Collections.emptyMap() : payload);
        } catch (Exception e) {
            // The artist page must still render if ranking is unavailable —
            // the section hides itself on an empty payload.
            log.error("[TerritoryRank] action=publicRead status=fail artistId={} err={}",
                    artistId, e.getMessage(), e);
            return ResponseEntity.ok(Collections.emptyMap());
        }
    }
}