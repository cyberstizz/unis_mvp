package com.unis.service;

import com.stripe.Stripe;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.unis.entity.Purchase;
import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.repository.PurchaseRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class DownloadService {

    private final SongRepository songRepository;
    private final PurchaseRepository purchaseRepository;
    private final UserRepository userRepository;

    @Value("${stripe.secret.key}")
    private String stripeSecretKey;

    // Platform fee percentage — 10%. Adjust as needed.
    private static final double PLATFORM_FEE_PERCENT = 0.10;

    // Minimum price in cents ($1.99) to cover Stripe processing fees
    private static final int MINIMUM_PRICE_CENTS = 199;

    public DownloadService(SongRepository songRepository,
                           PurchaseRepository purchaseRepository,
                           UserRepository userRepository) {
        this.songRepository = songRepository;
        this.purchaseRepository = purchaseRepository;
        this.userRepository = userRepository;
    }

    // ═══════════════════════════════════════════════════════════
    // UPDATE DOWNLOAD SETTINGS (called by artist)
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public Song updateDownloadSettings(UUID songId, UUID artistId,
                                       String downloadPolicy, Integer downloadPrice) {

        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new RuntimeException("Song not found: " + songId));

        // Verify ownership — getUserId() is the correct method on Unis User entity
        if (!song.getArtist().getUserId().equals(artistId)) {
            throw new RuntimeException("You do not own this song.");
        }

        if (!List.of("free", "paid", "unavailable").contains(downloadPolicy)) {
            throw new IllegalArgumentException(
                    "Invalid download policy. Must be: free, paid, or unavailable");
        }

        if ("paid".equals(downloadPolicy)) {
            if (downloadPrice == null || downloadPrice < MINIMUM_PRICE_CENTS) {
                throw new IllegalArgumentException(
                        "Paid downloads require a minimum price of $"
                                + String.format("%.2f", MINIMUM_PRICE_CENTS / 100.0));
            }
            song.setDownloadPolicy("paid");
            song.setDownloadPrice(downloadPrice);
        } else {
            song.setDownloadPolicy(downloadPolicy);
            song.setDownloadPrice(null);
        }

        Song saved = songRepository.save(song);
        log.info("Download settings updated for song {}: policy={}, price={}",
                songId, downloadPolicy, downloadPrice);
        return saved;
    }

    // ═══════════════════════════════════════════════════════════
    // CREATE PURCHASE INTENT (Stripe Direct Charge)
    //
    // Money goes straight to artist's connected Stripe account.
    // Unis takes application_fee (your 10% cut).
    // Returns client_secret for Stripe.js on the frontend.
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> createPurchaseIntent(UUID songId, UUID buyerId) {
        Stripe.apiKey = stripeSecretKey;

        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new RuntimeException("Song not found: " + songId));

        if (!"paid".equals(song.getDownloadPolicy())) {
            throw new RuntimeException("This song is not available for purchase.");
        }

        if (purchaseRepository.existsByBuyerIdAndSongId(buyerId, songId)) {
            throw new RuntimeException("You already own this song.");
        }

        // Get the artist's Stripe Connect account ID
        // IMPORTANT: Verify your User entity has this field.
        // It should have been added when you implemented Stripe Connect onboarding.
        // If the field is named differently (e.g. stripeConnectId), adjust here.
        UUID artistUserId = song.getArtist().getUserId();
        User artist = userRepository.findById(artistUserId)
                .orElseThrow(() -> new RuntimeException("Artist not found"));

        String artistStripeAccountId = artist.getStripeAccountId();
        if (artistStripeAccountId == null || artistStripeAccountId.isEmpty()) {
            throw new RuntimeException(
                    "This artist has not set up payouts yet. "
                            + "They need to connect their Stripe account before selling tracks.");
        }

        int priceInCents = song.getDownloadPrice();
        int platformFee = (int) Math.round(priceInCents * PLATFORM_FEE_PERCENT);

        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount((long) priceInCents)
                    .setCurrency("usd")
                    .setApplicationFeeAmount((long) platformFee)
                    .setTransferData(
                            PaymentIntentCreateParams.TransferData.builder()
                                    .setDestination(artistStripeAccountId)
                                    .build()
                    )
                    .putMetadata("song_id", songId.toString())
                    .putMetadata("buyer_id", buyerId.toString())
                    .putMetadata("artist_id", artistUserId.toString())
                    .putMetadata("type", "song_purchase")
                    .build();

            PaymentIntent paymentIntent = PaymentIntent.create(params);

            log.info("PaymentIntent created: {} for song {} buyer {} amount {}",
                    paymentIntent.getId(), songId, buyerId, priceInCents);

            Map<String, Object> response = new HashMap<>();
            response.put("clientSecret", paymentIntent.getClientSecret());
            response.put("paymentIntentId", paymentIntent.getId());
            response.put("amount", priceInCents);
            response.put("platformFee", platformFee);
            response.put("artistReceives", priceInCents - platformFee);
            return response;

        } catch (Exception e) {
            log.error("Failed to create PaymentIntent for song {}: {}", songId, e.getMessage());
            throw new RuntimeException("Payment processing failed. Please try again.");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CONFIRM PURCHASE (after Stripe.js confirms payment)
    //
    // Verifies PaymentIntent succeeded, records purchase,
    // returns download URL.
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> confirmPurchase(UUID songId, UUID buyerId,
                                                String paymentIntentId) {
        Stripe.apiKey = stripeSecretKey;

        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new RuntimeException("Song not found: " + songId));

        // Idempotency — if already purchased, just return the download URL
        if (purchaseRepository.existsByBuyerIdAndSongId(buyerId, songId)) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("downloadUrl", song.getFileUrl());
            response.put("alreadyOwned", true);
            return response;
        }

        try {
            PaymentIntent paymentIntent = PaymentIntent.retrieve(paymentIntentId);

            if (!"succeeded".equals(paymentIntent.getStatus())) {
                throw new RuntimeException(
                        "Payment not completed. Status: " + paymentIntent.getStatus());
            }

            // Security check — verify metadata matches
            String metaSongId = paymentIntent.getMetadata().get("song_id");
            String metaBuyerId = paymentIntent.getMetadata().get("buyer_id");
            if (!songId.toString().equals(metaSongId)
                    || !buyerId.toString().equals(metaBuyerId)) {
                throw new RuntimeException("Payment verification failed — metadata mismatch.");
            }

            int amount = paymentIntent.getAmount().intValue();
            int platformFee = paymentIntent.getApplicationFeeAmount() != null
                    ? paymentIntent.getApplicationFeeAmount().intValue() : 0;

            Purchase purchase = Purchase.builder()
                    .buyerId(buyerId)
                    .songId(songId)
                    .artistId(song.getArtist().getUserId())
                    .amount(amount)
                    .platformFee(platformFee)
                    .stripePaymentIntentId(paymentIntentId)
                    .status("completed")
                    .build();

            purchaseRepository.save(purchase);

            log.info("Purchase recorded: buyer {} bought song {} for {} cents",
                    buyerId, songId, amount);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("downloadUrl", song.getFileUrl());
            response.put("purchaseId", purchase.getId());
            return response;

        } catch (Exception e) {
            log.error("Purchase confirmation failed for song {}: {}", songId, e.getMessage());
            throw new RuntimeException("Purchase confirmation failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // GET DOWNLOAD INFO (determines DownloadModal state)
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> getDownloadInfo(UUID songId, UUID userId) {
        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new RuntimeException("Song not found: " + songId));

        Map<String, Object> response = new HashMap<>();
        response.put("songId", songId);
        response.put("title", song.getTitle());
        response.put("downloadPolicy", song.getDownloadPolicy());

        switch (song.getDownloadPolicy()) {
            case "free":
                response.put("canDownload", true);
                response.put("downloadUrl", song.getFileUrl());
                break;

            case "paid":
                response.put("price", song.getDownloadPrice());
                boolean alreadyPurchased = purchaseRepository
                        .existsByBuyerIdAndSongId(userId, songId);
                response.put("alreadyPurchased", alreadyPurchased);
                if (alreadyPurchased) {
                    response.put("canDownload", true);
                    response.put("downloadUrl", song.getFileUrl());
                } else {
                    response.put("canDownload", false);
                }
                break;

            case "unavailable":
            default:
                response.put("canDownload", false);
                break;
        }

        return response;
    }

    // ═══════════════════════════════════════════════════════════
    // PURCHASE HISTORY
    // ═══════════════════════════════════════════════════════════

    public List<Purchase> getBuyerPurchases(UUID buyerId) {
        return purchaseRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId);
    }

    public List<Purchase> getArtistSales(UUID artistId) {
        return purchaseRepository.findByArtistIdOrderByCreatedAtDesc(artistId);
    }
}