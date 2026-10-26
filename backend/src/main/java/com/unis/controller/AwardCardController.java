package com.unis.controller;

import com.unis.service.AwardCardService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * GET /api/v1/awards/{awardId}/card — the public share card behind /award/:id
 * (title, winner, period, photo unless the winner is under 17, caption).
 * Public and rate-limited; see AwardCardService.
 */
@RestController
@RequestMapping("/api/v1/awards")
public class AwardCardController {

    private final AwardCardService cards;

    public AwardCardController(AwardCardService cards) {
        this.cards = cards;
    }

    @GetMapping("/{awardId}/card")
    public ResponseEntity<?> card(@PathVariable UUID awardId) {
        return cards.card(awardId)
                .<ResponseEntity<?>>map(c -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                        .body(c))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "This award isn't available.")));
    }
}
