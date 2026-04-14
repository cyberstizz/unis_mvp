package com.unis.controller;

import com.unis.entity.Purchase;
import com.unis.entity.Song;
import com.unis.service.DownloadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/songs")
@Slf4j
public class DownloadController {

    private final DownloadService downloadService;

    public DownloadController(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    // ═══════════════════════════════════════════════════════════
    // STEP 3 ENDPOINT: Update Download Settings
    //
    // PUT /api/v1/songs/{songId}/download-settings
    //
    // Body: { "downloadPolicy": "paid", "downloadPrice": 500 }
    //
    // Called by the artist from:
    //   - EditWizard (changing settings on existing song)
    //   - ArtistDashboard (quick toggle)
    //
    // The UploadWizard and CreateAccountWizard send these fields
    // as part of the normal song creation payload, so they don't
    // need this endpoint — they just include downloadPolicy and
    // downloadPrice in the song creation request body.
    // ═══════════════════════════════════════════════════════════

    @PutMapping("/{songId}/download-settings")
    public ResponseEntity<?> updateDownloadSettings(
            @PathVariable UUID songId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UUID userId) {  // Adjust based on your auth setup
        try {
            String policy = (String) body.get("downloadPolicy");
            Integer price = body.get("downloadPrice") != null
                    ? Integer.parseInt(body.get("downloadPrice").toString())
                    : null;

            Song updated = downloadService.updateDownloadSettings(songId, userId, policy, price);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "songId", updated.getSongId(),
                    "downloadPolicy", updated.getDownloadPolicy(),
                    "downloadPrice", updated.getDownloadPrice() != null ? updated.getDownloadPrice() : 0
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // STEP 4A ENDPOINT: Create Purchase Intent (start payment)
    //
    // POST /api/v1/songs/{songId}/purchase
    //
    // Returns: { "clientSecret": "pi_xxx_secret_xxx", "amount": 500, ... }
    //
    // Called when buyer clicks "Purchase & Download" in DownloadModal.
    // The frontend takes the clientSecret and uses it with Stripe.js
    // to show the card form and confirm payment.
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/{songId}/purchase")
    public ResponseEntity<?> createPurchaseIntent(
            @PathVariable UUID songId,
            @AuthenticationPrincipal UUID userId) {
        try {
            Map<String, Object> result = downloadService.createPurchaseIntent(songId, userId);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // STEP 4B ENDPOINT: Confirm Purchase (after card charged)
    //
    // POST /api/v1/songs/{songId}/purchase/confirm
    //
    // Body: { "paymentIntentId": "pi_xxx" }
    //
    // Returns: { "success": true, "downloadUrl": "https://..." }
    //
    // Called by frontend AFTER Stripe.js confirms the payment.
    // This records the purchase and returns the download URL.
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/{songId}/purchase/confirm")
    public ResponseEntity<?> confirmPurchase(
            @PathVariable UUID songId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UUID userId) {
        try {
            String paymentIntentId = body.get("paymentIntentId");
            if (paymentIntentId == null || paymentIntentId.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "paymentIntentId is required"));
            }

            Map<String, Object> result = downloadService
                    .confirmPurchase(songId, userId, paymentIntentId);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // STEP 5 ENDPOINT: Get Download Info
    //
    // GET /api/v1/songs/{songId}/download
    //
    // Returns download policy, price, whether user already owns it,
    // and the download URL if they have access.
    //
    // Called by frontend when user clicks the download button —
    // this determines which state the DownloadModal shows.
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/{songId}/download")
    public ResponseEntity<?> getDownloadInfo(
            @PathVariable UUID songId,
            @AuthenticationPrincipal UUID userId) {
        try {
            Map<String, Object> result = downloadService.getDownloadInfo(songId, userId);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // HELPER ENDPOINTS: Purchase History
    // ═══════════════════════════════════════════════════════════

    // GET /api/v1/songs/my-purchases — songs the current user has bought
    @GetMapping("/my-purchases")
    public ResponseEntity<?> getMyPurchases(@AuthenticationPrincipal UUID userId) {
        try {
            List<Purchase> purchases = downloadService.getBuyerPurchases(userId);
            return ResponseEntity.ok(purchases);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // GET /api/v1/songs/my-sales — songs other users have bought from the current artist
    @GetMapping("/my-sales")
    public ResponseEntity<?> getMySales(@AuthenticationPrincipal UUID userId) {
        try {
            List<Purchase> sales = downloadService.getArtistSales(userId);
            return ResponseEntity.ok(sales);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}