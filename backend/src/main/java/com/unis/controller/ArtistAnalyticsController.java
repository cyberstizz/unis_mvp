package com.unis.controller;

import com.unis.service.ArtistFanbaseService;
import com.unis.service.TerritoryRankService;   // ★ territory
import com.unis.util.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/artist-analytics")
public class ArtistAnalyticsController {

    private final ArtistFanbaseService artistFanbaseService;
    private final TerritoryRankService territoryRankService;   // ★ territory

    // ★ territory: TerritoryRankService injected alongside the fanbase service
    public ArtistAnalyticsController(ArtistFanbaseService artistFanbaseService,
                                     TerritoryRankService territoryRankService) {
        this.artistFanbaseService = artistFanbaseService;
        this.territoryRankService = territoryRankService;
    }

/**
     * Fanbase funnel + recent named supporters + 30-day supporter growth.
     * Only the artist may view their own fanbase analytics.
     *
     * ★ period: optional (today|week|month|year|all), defaults "all".
     * ★ item 5: optional drill-down filters — gender, age bucket, and home
     * jurisdiction. All null/absent → original unfiltered behavior.
     */
    @GetMapping("/artist/{artistId}/fanbase")
    public ResponseEntity<Map<String, Object>> getArtistFanbase(
            @PathVariable UUID artistId,
            @RequestParam(name = "period", required = false, defaultValue = "all") String period,
            @RequestParam(name = "gender", required = false) String gender,
            @RequestParam(name = "age", required = false) String age,
            @RequestParam(name = "jurisdictionId", required = false) UUID jurisdictionId) {

        UUID requesterId = SecurityUtils.getAuthenticatedUserId();

        if (!requesterId.equals(artistId)) {
            return ResponseEntity.status(403).build();
        }

        return ResponseEntity.ok(
            artistFanbaseService.getArtistFanbase(artistId, period, gender, age, jurisdictionId));
    }


    /**
     * ★ item 5: supporters split out of the funnel — all-time named grid,
     * #1 supporter, and 30-day growth. Artist-only.
     */
    @GetMapping("/artist/{artistId}/supporters")
    public ResponseEntity<Map<String, Object>> getArtistSupporters(
            @PathVariable UUID artistId) {

        UUID requesterId = SecurityUtils.getAuthenticatedUserId();
        if (!requesterId.equals(artistId)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(artistFanbaseService.getArtistSupporters(artistId));
    }

    /**
     * ★ Per-song funnel (listeners → likers → voters → followers → supporters)
     * scoped to a single song, with optional period. Artist-owns-song enforced.
     */
    @GetMapping("/artist/{artistId}/song/{songId}/funnel")
    public ResponseEntity<Map<String, Object>> getSongFunnel(
            @PathVariable UUID artistId,
            @PathVariable UUID songId,
            @RequestParam(name = "period", required = false, defaultValue = "all") String period,
            @RequestParam(name = "gender", required = false) String gender,            // ★ filters
            @RequestParam(name = "age", required = false) String age,                  // ★
            @RequestParam(name = "jurisdictionId", required = false) UUID jurisdictionId) {  // ★

        UUID requesterId = SecurityUtils.getAuthenticatedUserId();
        if (!requesterId.equals(artistId)) {
            return ResponseEntity.status(403).build();
        }

        Map<String, Object> result =
            artistFanbaseService.getSongFunnel(artistId, songId, period, gender, age, jurisdictionId); // ★
        if (result == null) {
            return ResponseEntity.status(404).build(); // song not found / not owned
        }
        return ResponseEntity.ok(result);
    }



    /**
     * ★ Per-song sales summary + daily time-series. Artist-owns-song enforced.
     */
    @GetMapping("/artist/{artistId}/song/{songId}/sales")
    public ResponseEntity<Map<String, Object>> getSongSales(
            @PathVariable UUID artistId,
            @PathVariable UUID songId) {

        UUID requesterId = SecurityUtils.getAuthenticatedUserId();
        if (!requesterId.equals(artistId)) {
            return ResponseEntity.status(403).build();
        }

        Map<String, Object> result = artistFanbaseService.getSongSales(artistId, songId);
        if (result == null) {
            return ResponseEntity.status(404).build();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/artist/{artistId}/sales-total") // ★ artist-level sales total for RevenueSection
    public ResponseEntity<Map<String, Object>> getArtistSalesTotal(@PathVariable UUID artistId) {
        UUID requesterId = SecurityUtils.getAuthenticatedUserId();
        if (!requesterId.equals(artistId)) {
            return ResponseEntity.status(403).build();
        }
        Map<String, Object> result = artistFanbaseService.getArtistSalesTotal(artistId);
        return ResponseEntity.ok(result);
    }

    /**
     * ★ item 6: pie data — top jurisdictions by metric. plays/listeners are
     * play-location; likes/followers/supporters are home jurisdiction.
     */
    @GetMapping("/artist/{artistId}/demographics/top-jurisdictions")
    public ResponseEntity<Map<String, Object>> getTopJurisdictions(
            @PathVariable UUID artistId,
            @RequestParam(name = "period", required = false, defaultValue = "all") String period,
            @RequestParam(name = "metric", required = false, defaultValue = "plays") String metric) {

        UUID requesterId = SecurityUtils.getAuthenticatedUserId();
        if (!requesterId.equals(artistId)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(artistFanbaseService.getTopJurisdictions(artistId, period, metric));
    }

    /**
     * ★ item 6: territory drill-down — subtree-rolled stats for one
     * jurisdiction + its children. No jurisdictionId = root.
     */
    @GetMapping("/artist/{artistId}/demographics/territory")
    public ResponseEntity<Map<String, Object>> getTerritory(
            @PathVariable UUID artistId,
            @RequestParam(name = "jurisdictionId", required = false) UUID jurisdictionId,
            @RequestParam(name = "period", required = false, defaultValue = "all") String period) {

        UUID requesterId = SecurityUtils.getAuthenticatedUserId();
        if (!requesterId.equals(artistId)) {
            return ResponseEntity.status(403).build();
        }
        Map<String, Object> result = artistFanbaseService.getTerritory(artistId, jurisdictionId, period);
        if (result == null) {
            return ResponseEntity.status(404).build();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * ★ territory: precomputed Territory Rank for the artist — overall +
     * category rank in every jurisdiction in their home chain (neighborhood →
     * national), across all six period windows, in one indexed read. Returns
     * status "calculating" until the nightly job (or admin manual trigger)
     * has populated jurisdiction_ranks. Artist-only.
     */
    @GetMapping("/artist/{artistId}/territory-rank")
    public ResponseEntity<Map<String, Object>> getTerritoryRank(
            @PathVariable UUID artistId) {

        UUID requesterId = SecurityUtils.getAuthenticatedUserId();
        if (!requesterId.equals(artistId)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(territoryRankService.getTerritoryRank(artistId));
    }

}