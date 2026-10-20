package com.unis.controller;

import com.unis.service.ModerationService;
import com.unis.service.SponsorService;
import com.unis.service.StripeApi;
import com.unis.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Admin → Sponsors: reviewing local sponsorships.
 *
 *   GET  /api/v1/admin/sponsors                 paid sponsorships, waiting-for-review first
 *   POST /api/v1/admin/sponsors/{id}/approve    it runs in its weeks
 *   POST /api/v1/admin/sponsors/{id}/reject     { "note": "why" } — refunds the full amount
 *
 * Reading and approving: admin and super admin. Rejecting refunds money, so
 * super admin only (SecurityConfig). Both are written to the audit log.
 */
@RestController
@RequestMapping("/api/v1/admin/sponsors")
public class AdminSponsorController {

    private static final Logger log = LoggerFactory.getLogger(AdminSponsorController.class);

    private final SponsorService sponsors;
    private final ModerationService moderationService;

    public AdminSponsorController(SponsorService sponsors, ModerationService moderationService) {
        this.sponsors = sponsors;
        this.moderationService = moderationService;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(sponsors.list(limit));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable UUID id) {
        UUID by = SecurityUtils.getAuthenticatedUserId();
        try {
            sponsors.approve(id, by);
            moderationService.logAction(by, "sponsor_approved", "sponsor", id, null, "Local sponsorship approved");
            return ResponseEntity.ok(Map.of("status", "approved"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        UUID by = SecurityUtils.getAuthenticatedUserId();
        String note = body == null || body.get("note") == null ? null : String.valueOf(body.get("note"));
        try {
            sponsors.reject(id, note, by);
            moderationService.logAction(by, "sponsor_rejected", "sponsor", id, note, "Rejected and refunded in full");
            return ResponseEntity.ok(Map.of("status", "refunded"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (StripeApi.StripeException e) {
            log.warn("[Sponsor] action=reject campaign={} status=stripe_error err={}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Stripe didn't accept the refund: " + e.getMessage()));
        }
    }
}
