package com.unis.controller;

import com.unis.service.PlusService;
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
 * Unis Plus, the ad-free plan.
 *
 *   GET  /api/v1/plus/status     anyone: is Plus on sale, the price, and (signed in)
 *                                whether this account is ad-free and until when
 *   POST /api/v1/plus/checkout   signed in: start Stripe Checkout → { url }
 *   POST /api/v1/plus/portal     signed in: Stripe's customer portal (card, cancel) → { url }
 */
@RestController
@RequestMapping("/api/v1/plus")
public class PlusController {

    private static final Logger log = LoggerFactory.getLogger(PlusController.class);

    private final PlusService plus;

    public PlusController(PlusService plus) {
        this.plus = plus;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(plus.status(currentUserOrNull()));
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout() {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        try {
            return ResponseEntity.ok(Map.of("url", plus.startCheckout(userId)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (StripeApi.StripeException e) {
            log.warn("[Plus] action=checkout userId={} status=stripe_error err={}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Checkout couldn't start. Please try again in a minute."));
        }
    }

    @PostMapping("/portal")
    public ResponseEntity<?> portal() {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        try {
            return ResponseEntity.ok(Map.of("url", plus.portal(userId)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (StripeApi.StripeException e) {
            log.warn("[Plus] action=portal userId={} status=stripe_error err={}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Couldn't open plan settings. Please try again in a minute."));
        }
    }

    private static UUID currentUserOrNull() {
        try {
            return SecurityUtils.getAuthenticatedUserId();
        } catch (Exception e) {
            return null;
        }
    }
}
