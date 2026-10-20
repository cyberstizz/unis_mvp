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
    // AD VIEW TRACKING — retired
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/v1/earnings/track-view — no longer records anything.
     *
     * Ad views used to credit the viewer's supported artist and referrers.
     * Google's ad rules forbid paying anyone because ads were viewed, so the
     * money now follows listening through the monthly Community Share. The
     * endpoint stays (answering "not tracked") so app builds that still call
     * it don't show errors.
     */
    @PostMapping("/track-view")
    public ResponseEntity<?> trackAdView() {
        return ResponseEntity.ok(Map.of("tracked", false, "retired", true));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EARNINGS DASHBOARD (Authenticated user — any role)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/v1/earnings/my-summary
     * The signed-in person's earnings: balance, lifetime and last closed
     * month by level, and this month's listening so far in their network.
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
     * The people this user invited directly: plays this month and what their
     * listening has earned this user at level 1.
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
     * GET /api/v1/earnings/my-history?months=12
     * Month-by-month earnings for the chart, oldest first. The current month
     * shows 0 until it's closed. (Older app builds send ?days=30; that's
     * turned into a number of months.)
     */
    @GetMapping("/my-history")
    public ResponseEntity<?> getMyHistory(
            @RequestParam(required = false) Integer months,
            @RequestParam(required = false) Integer days) {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            return ResponseEntity.ok(earningsService.getMonthlyHistory(userId, monthsFrom(months, days)));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Failed to load history: " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEGACY ENDPOINTS (kept for backward compatibility)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/v1/earnings/{artistId}?months=12
     * Original artist-specific endpoint; now monthly like /my-history.
     */
    @GetMapping("/{artistId}")
    public ResponseEntity<?> getEarnings(
            @PathVariable UUID artistId,
            @RequestParam(required = false) Integer months,
            @RequestParam(required = false) Integer days) {
        if (!isSelfOrAdmin(artistId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You can only see your own earnings"));
        }
        List<Map<String, Object>> history = earningsService.getMonthlyHistory(artistId, monthsFrom(months, days));
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
        if (!isSelfOrAdmin(artistId)) {
            return ResponseEntity.status(403).body(Map.of("error", "You can only see your own earnings"));
        }
        Map<String, Object> summary = earningsService.getEarningsSummary(artistId);
        return ResponseEntity.ok(summary);
    }

    /** ?months wins; otherwise ?days rounded up to months; otherwise 12. */
    private static int monthsFrom(Integer months, Integer days) {
        if (months != null) return Math.max(1, Math.min(months, 24));
        if (days != null) return Math.max(1, Math.min((days + 29) / 30, 24));
        return 12;
    }

    /**
     * Earnings are private: the artist themselves or an admin. These two
     * endpoints used to answer for any artist ID to anyone signed in.
     */
    private static boolean isSelfOrAdmin(UUID artistId) {
        UUID me;
        try {
            me = SecurityUtils.getAuthenticatedUserId();
        } catch (Exception e) {
            return false;
        }
        if (me.equals(artistId)) return true;
        org.springframework.security.core.Authentication auth =
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}