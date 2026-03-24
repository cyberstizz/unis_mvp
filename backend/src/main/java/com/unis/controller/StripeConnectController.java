package com.unis.controller;

import com.unis.service.StripeConnectService;
import com.unis.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stripe")
public class StripeConnectController {

    @Autowired
    private StripeConnectService stripeConnectService;

    // ═══════════════════════════════════════════════════════════════════════════
    // ONBOARDING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/v1/stripe/onboard
     * Creates a Stripe Express account (if needed) and returns an onboarding URL.
     * The frontend redirects the user to this URL.
     */
    @PostMapping("/onboard")
    public ResponseEntity<?> startOnboarding() {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            String onboardingUrl = stripeConnectService.createOnboardingLink(userId);
            return ResponseEntity.ok(Map.of("url", onboardingUrl));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Failed to start onboarding: " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCOUNT STATUS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/v1/stripe/status
     * Returns the current Stripe Connect status for the authenticated user.
     */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            Map<String, Object> status = stripeConnectService.getAccountStatus(userId);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Failed to check status: " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAYOUTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/v1/stripe/payout
     * Request a payout. Validates balance >= $50, no active payouts, and
     * Stripe account is complete.
     */
    @PostMapping("/payout")
    public ResponseEntity<?> requestPayout() {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            var payout = stripeConnectService.requestPayout(userId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "payoutId", payout.getPayoutId(),
                    "amount", payout.getAmount(),
                    "status", payout.getStatus()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Payout failed: " + e.getMessage()));
        }
    }

    /**
     * GET /api/v1/stripe/payouts
     * Returns the user's payout history.
     */
    @GetMapping("/payouts")
    public ResponseEntity<?> getPayoutHistory() {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            List<Map<String, Object>> history = stripeConnectService.getPayoutHistory(userId);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Failed to load payouts: " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STRIPE DASHBOARD
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/v1/stripe/dashboard-link
     * Returns a one-time login URL to the user's Stripe Express dashboard
     * where they can manage bank details and view payout schedule.
     */
    @GetMapping("/dashboard-link")
    public ResponseEntity<?> getDashboardLink() {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            String url = stripeConnectService.createDashboardLink(userId);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Failed to generate dashboard link: " + e.getMessage()));
        }
    }
}