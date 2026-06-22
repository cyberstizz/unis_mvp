package com.unis.controller;

import com.unis.entity.SupportPayment;
import com.unis.service.DirectSupportService;
import com.unis.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Direct fan-to-artist support ("tips").
 *
 * Mirrors the song-purchase two-step flow:
 *   1. POST /{artistId}/intent   → create a Stripe destination-charge intent,
 *                                   returns clientSecret for Stripe.js
 *   2. POST /{artistId}/confirm  → verify + record the SupportPayment
 */
@RestController
@RequestMapping("/api/v1/support")
@Slf4j
public class DirectSupportController {

    private final DirectSupportService directSupportService;

    public DirectSupportController(DirectSupportService directSupportService) {
        this.directSupportService = directSupportService;
    }

    // ═══════════════════════════════════════════════════════════
    // CREATE SUPPORT INTENT
    //
    // POST /api/v1/support/{artistId}/intent
    // Body: { "amount": 1000, "note": "keep going", "source": "dm" }
    //   amount is in cents; note + source optional.
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/{artistId}/intent")
    public ResponseEntity<?> createSupportIntent(@PathVariable UUID artistId,
                                                 @RequestBody Map<String, Object> body) {
        try {
            UUID supporterId = SecurityUtils.getAuthenticatedUserId();

            if (body.get("amount") == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "amount (in cents) is required"));
            }
            int amount = (int) Math.round(Double.parseDouble(body.get("amount").toString()));
            String note = body.get("note") != null ? body.get("note").toString() : null;
            String source = body.get("source") != null ? body.get("source").toString() : "profile";

            Map<String, Object> result =
                    directSupportService.createSupportIntent(artistId, supporterId, amount, note, source);
            return ResponseEntity.ok(result);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "amount must be a whole number of cents"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CONFIRM SUPPORT
    //
    // POST /api/v1/support/{artistId}/confirm
    // Body: { "paymentIntentId": "pi_xxx" }
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/{artistId}/confirm")
    public ResponseEntity<?> confirmSupport(@PathVariable UUID artistId,
                                            @RequestBody Map<String, String> body) {
        try {
            UUID supporterId = SecurityUtils.getAuthenticatedUserId();

            String paymentIntentId = body.get("paymentIntentId");
            if (paymentIntentId == null || paymentIntentId.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "paymentIntentId is required"));
            }

            Map<String, Object> result =
                    directSupportService.confirmSupport(artistId, supporterId, paymentIntentId);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ARTIST SUPPORT STATS (for the public profile / dashboard)
    //
    // GET /api/v1/support/{artistId}/stats
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/{artistId}/stats")
    public ResponseEntity<?> getArtistSupportStats(@PathVariable UUID artistId) {
        try {
            return ResponseEntity.ok(directSupportService.getArtistSupportStats(artistId));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // HISTORY
    // ═══════════════════════════════════════════════════════════

    // GET /api/v1/support/received  — support this artist has received
    @GetMapping("/received")
    public ResponseEntity<?> getReceived() {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            List<SupportPayment> received = directSupportService.getReceivedSupport(userId);
            return ResponseEntity.ok(received);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    // GET /api/v1/support/sent  — support this listener has sent
    @GetMapping("/sent")
    public ResponseEntity<?> getSent() {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            List<SupportPayment> sent = directSupportService.getSentSupport(userId);
            return ResponseEntity.ok(sent);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}