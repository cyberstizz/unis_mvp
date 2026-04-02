package com.unis.controller;

import com.unis.dto.PreRegistrationRequest;
import com.unis.dto.PreRegistrationResponse;
import com.unis.service.PreRegistrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class WaitlistController {

    @Autowired
    private PreRegistrationService preRegService;

    // ═══════════════════════════════════════════════════════
    //  PUBLIC ENDPOINTS (no auth required)
    // ═══════════════════════════════════════════════════════

    /**
     * POST /v1/waitlist/register
     * Sign up for the waitlist from anywhere in the US.
     */
    @PostMapping("/api/v1/waitlist/register")
    public ResponseEntity<?> register(@RequestBody PreRegistrationRequest request) {
        try {
            PreRegistrationResponse response = preRegService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /v1/waitlist/check-referral/{code}
     * Validate a referral code before form submission.
     */
    @GetMapping("/api/v1/waitlist/check-referral/{code}")
    public ResponseEntity<?> checkReferral(@PathVariable String code) {
        return preRegService.findByReferralCode(code)
                .map(r -> ResponseEntity.ok(Map.of(
                        "valid", true,
                        "referrerUsername", r.getUsername(),
                        "referrerRegion", r.getMetroRegion()
                )))
                .orElse(ResponseEntity.ok(Map.of("valid", false)));
    }

    /**
     * GET /v1/waitlist/region-progress?state={XX}&metro={region}
     * Check how close a region is to activation. Public so the
     * confirmation screen can show "247 of 500 signed up!"
     */
    @GetMapping("/api/v1/waitlist/region-progress")
    public ResponseEntity<?> regionProgress(
            @RequestParam String state,
            @RequestParam String metro) {
        return ResponseEntity.ok(preRegService.getRegionProgress(state, metro));
    }

    // ═══════════════════════════════════════════════════════
    //  ADMIN ENDPOINTS (add your existing admin auth filter)
    // ═══════════════════════════════════════════════════════

    /**
     * GET /v1/admin/analytics/waitlist
     * Full waitlist overview for the admin dashboard.
     */
    @GetMapping("/api/v1/admin/analytics/waitlist")
    public ResponseEntity<?> waitlistOverview() {
        return ResponseEntity.ok(preRegService.getWaitlistOverview());
    }

    /**
     * GET /v1/admin/analytics/waitlist/daily?days=30
     * Daily signup trend for chart rendering.
     */
    @GetMapping("/api/v1/admin/analytics/waitlist/daily")
    public ResponseEntity<?> waitlistDaily(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(preRegService.getWaitlistDailySignups(days));
    }
}