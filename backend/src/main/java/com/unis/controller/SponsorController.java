package com.unis.controller;

import com.unis.service.SponsorService;
import com.unis.service.StripeApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * Local sponsors — public, no account needed (shop owners arrive from a QR code).
 *
 *   GET  /api/v1/sponsors/options     areas, weekly prices, weeks with spots left
 *   POST /api/v1/sponsors/checkout    the form (multipart, with an optional logo) → { url } of Stripe Checkout
 *   GET  /api/v1/sponsors/serve       one live sponsor for an ad slot (?jurisdictionId=…), or 204
 *   GET  /api/v1/sponsors/{id}/go     counts a tap and redirects to the sponsor's link
 */
@RestController
@RequestMapping("/api/v1/sponsors")
public class SponsorController {

    private static final Logger log = LoggerFactory.getLogger(SponsorController.class);

    private final SponsorService sponsors;

    @Value("${app.frontend.url:https://unisprototypetwo.netlify.app}")
    private String frontendUrl;

    public SponsorController(SponsorService sponsors) {
        this.sponsors = sponsors;
    }

    @GetMapping("/options")
    public ResponseEntity<?> options() {
        return ResponseEntity.ok(sponsors.options());
    }

    @PostMapping(value = "/checkout", consumes = "multipart/form-data")
    public ResponseEntity<?> checkout(@RequestParam(required = false) String businessName,
                                      @RequestParam(required = false) String contactEmail,
                                      @RequestParam(required = false) String headline,
                                      @RequestParam(required = false) String link,
                                      @RequestParam(required = false) String area,
                                      @RequestParam(required = false) String weekStart,
                                      @RequestParam(defaultValue = "1") int weeks,
                                      @RequestParam(required = false) String ref,
                                      @RequestParam(required = false) MultipartFile logo) {
        try {
            String url = sponsors.startCheckout(businessName, contactEmail, headline, link, area, weekStart,
                    weeks, ref, logo);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (StripeApi.StripeException e) {
            log.warn("[Sponsor] action=checkout status=stripe_error err={}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Checkout couldn't start. Please try again in a minute."));
        }
    }

    @GetMapping("/serve")
    public ResponseEntity<?> serve(@RequestParam(required = false) UUID jurisdictionId) {
        Map<String, Object> sponsor = sponsors.serve(jurisdictionId);
        if (sponsor == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(sponsor);
    }

    @GetMapping("/{id}/go")
    public ResponseEntity<Void> go(@PathVariable UUID id) {
        String link = sponsors.click(id);
        String target = link != null ? link : frontendUrl;
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(target));
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }
}
