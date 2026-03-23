package com.unis.controller;

import com.unis.service.EarningsService;
import com.unis.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/earnings")
public class EarningsController {

    @Autowired
    private EarningsService earningsService;

    // ═══════════════════════════════════════════════════════════════════════════
    // AD VIEW TRACKING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/v1/earnings/track-view
     * Called by the frontend when a user sees a display ad.
     * Uses JWT to identify the viewer — no client-supplied userId.
     */
    @PostMapping("/track-view")
    public ResponseEntity<?> trackAdView() {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            earningsService.trackAdView(userId);
            return ResponseEntity.ok(Map.of("tracked", true));
        } catch (Exception e) {
            // Silent failure — ad tracking should never block the user experience
            return ResponseEntity.ok(Map.of("tracked", false));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EARNINGS DASHBOARD (Authenticated user — any role)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/v1/earnings/my-summary
     * Returns the authenticated user's complete earnings summary.
     * Works for both listeners (referral earnings only) and
     * artists (referral + supporter earnings).
     */
    @GetMapping("/my-summary")
    public ResponseEntity<?> getMySummary() {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            Map<String, Object> summary = earningsService.getEarningsSummary(userId);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Failed to load earnings: " + e.getMessage()));
        }
    }

    /**
     * GET /api/v1/earnings/my-referrals
     * Returns a list of the user's referrals with their individual
     * ad view counts and earnings contributions.
     */
    @GetMapping("/my-referrals")
    public ResponseEntity<?> getMyReferrals() {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            List<Map<String, Object>> referrals = earningsService.getReferralBreakdown(userId);
            return ResponseEntity.ok(referrals);
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Failed to load referrals: " + e.getMessage()));
        }
    }

    /**
     * GET /api/v1/earnings/my-history?days=30
     * Returns daily earnings breakdown for charting.
     * Each entry contains referral earnings, supporter earnings, and total.
     */
    @GetMapping("/my-history")
    public ResponseEntity<?> getMyHistory(@RequestParam(defaultValue = "30") int days) {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            List<Map<String, Object>> history = earningsService.getDailyHistory(userId, days);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Failed to load history: " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEGACY ENDPOINTS (kept for backward compatibility)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/v1/earnings/{artistId}?days=30
     * Original artist-specific endpoint.
     */
    @GetMapping("/{artistId}")
    public ResponseEntity<?> getEarnings(
            @PathVariable UUID artistId,
            @RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> history = earningsService.getDailyHistory(artistId, days);
        return ResponseEntity.ok(history);
    }

    /**
     * GET /api/v1/earnings/{artistId}/breakdown?days=30
     * Original artist-specific breakdown.
     */
    @GetMapping("/{artistId}/breakdown")
    public ResponseEntity<?> getEarningsBreakdown(
            @PathVariable UUID artistId,
            @RequestParam(defaultValue = "30") int days) {
        Map<String, Object> summary = earningsService.getEarningsSummary(artistId);
        return ResponseEntity.ok(summary);
    }
}