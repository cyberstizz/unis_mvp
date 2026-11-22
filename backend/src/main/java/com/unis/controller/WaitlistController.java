package com.unis.controller;

import com.unis.dto.PreRegistrationRequest;
import com.unis.service.EmailVerificationService;
import com.unis.service.HomeAreaService;
import com.unis.service.PreRegistrationService;
import com.unis.service.VisitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class WaitlistController {

    private static final Logger log = LoggerFactory.getLogger(WaitlistController.class);

    @Autowired
    private PreRegistrationService preRegService;

    @Autowired
    private VisitorService visitorService;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private HomeAreaService homeAreaService;

    // ═══════════════════════════════════════════════════════
    //  PUBLIC ENDPOINTS (no auth required)
    // ═══════════════════════════════════════════════════════

    /**
     * POST /v1/waitlist/register
     * Join from anywhere in the US. Writes the city's waitlist entry AND a
     * visitor account (VisitorService), then emails the link to confirm the
     * address. visitorHeld in the answer says the account can't sign in yet
     * (the visitor switch is closed or today's cap was reached).
     */
    @PostMapping("/api/v1/waitlist/register")
    public ResponseEntity<?> register(@RequestBody PreRegistrationRequest request) {
        try {
            VisitorService.Signup signup = visitorService.register(request);
            // After the save is committed, so the link always points at a real account.
            try {
                emailVerificationService.sendVerification(signup.user());
            } catch (Exception e) {
                // The account exists; "Resend" on the next screen and sign-in both send it again.
                log.error("[Visitor] action=verification_email status=error userId={} err={}",
                        signup.user().getUserId(), e.getMessage());
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(signup.response());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /v1/waitlist/home-area  { "address": "123 W 125th St, New York, NY" }
     * The sign-up's "Find my area" button: is this address in Uptown Harlem,
     * Downtown Harlem, or outside Harlem? Looked up on the server through the
     * US Census Bureau's geocoder (HomeAreaService), so the browser no longer
     * sends people's home addresses to OpenStreetMap's volunteer-run server.
     * POST so the address never sits in a URL or a log line.
     */
    @PostMapping("/api/v1/waitlist/home-area")
    public ResponseEntity<?> homeArea(@RequestBody Map<String, String> body) {
        String address = body == null ? null : body.get("address");
        try {
            HomeAreaService.Result r = homeAreaService.lookup(address);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("found", r.found());
            out.put("inHarlem", r.inHarlem());
            out.put("area", r.area());          // "uptown-harlem" | "downtown-harlem" | null
            out.put("areaName", r.areaName());  // "Uptown Harlem" | "Downtown Harlem" | null
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (HomeAreaService.LookupUnavailable e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error",
                    "We couldn't check that address right now. Choose your area below instead."));
        }
    }

    /**
     * GET /v1/waitlist/check-referral/{code}
     * Validate a referral code before form submission. The code is required;
     * it can belong to someone on the waitlist, a member, or be a short code.
     */
    @GetMapping("/api/v1/waitlist/check-referral/{code}")
    public ResponseEntity<?> checkReferral(@PathVariable String code) {
        return preRegService.checkCode(code)
                .<ResponseEntity<?>>map(h -> ResponseEntity.ok(Map.of(
                        "valid", true,
                        "referrerUsername", h.username()
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
