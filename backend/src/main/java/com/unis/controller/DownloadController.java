package com.unis.controller;

import com.unis.entity.Purchase;
import com.unis.entity.Song;
import com.unis.service.DownloadService;
import com.unis.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    // UPDATE DOWNLOAD SETTINGS (artist only)
    //
    // PUT /api/v1/songs/{songId}/download-settings
    // Body: { "downloadPolicy": "paid", "downloadPrice": 500 }
    // ═══════════════════════════════════════════════════════════

    @PutMapping("/{songId}/download-settings")
    public ResponseEntity<?> updateDownloadSettings(
            @PathVariable UUID songId,
            @RequestBody Map<String, Object> body) {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();

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
    // CREATE PURCHASE INTENT (buyer starts payment)
    //
    // POST /api/v1/songs/{songId}/purchase
    // Returns: { "clientSecret": "pi_xxx_secret_xxx", ... }
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/{songId}/purchase")
    public ResponseEntity<?> createPurchaseIntent(@PathVariable UUID songId) {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            Map<String, Object> result = downloadService.createPurchaseIntent(songId, userId);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CONFIRM PURCHASE (after Stripe.js charges the card)
    //
    // POST /api/v1/songs/{songId}/purchase/confirm
    // Body: { "paymentIntentId": "pi_xxx" }
    // Returns: { "success": true, "downloadUrl": "https://..." }
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/{songId}/purchase/confirm")
    public ResponseEntity<?> confirmPurchase(
            @PathVariable UUID songId,
            @RequestBody Map<String, String> body) {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();

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
    // GET DOWNLOAD INFO (determines what DownloadModal shows)
    //
    // GET /api/v1/songs/{songId}/download
    // Returns: { downloadPolicy, price, canDownload, downloadUrl }
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/{songId}/download")
    public ResponseEntity<?> getDownloadInfo(@PathVariable UUID songId) {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            Map<String, Object> result = downloadService.getDownloadInfo(songId, userId);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PURCHASE HISTORY
    // ═══════════════════════════════════════════════════════════

    // GET /api/v1/songs/my-purchases
    @GetMapping("/my-purchases")
    public ResponseEntity<?> getMyPurchases() {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            List<Purchase> purchases = downloadService.getBuyerPurchases(userId);
            return ResponseEntity.ok(purchases);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // GET /api/v1/songs/my-sales
    @GetMapping("/my-sales")
    public ResponseEntity<?> getMySales() {
        try {
            UUID userId = SecurityUtils.getAuthenticatedUserId();
            List<Purchase> sales = downloadService.getArtistSales(userId);
            return ResponseEntity.ok(sales);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}