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

    // Your Stripe secret key — should already be in application.properties
    @Value("${stripe.secret.key}")
    private String stripeSecretKey;

    // Platform fee percentage (10% = 0.10). Adjust as you decide.
    private static final double PLATFORM_FEE_PERCENT = 0.10;

    // Minimum price in cents ($1.99) to cover Stripe fees
    private static final int MINIMUM_PRICE_CENTS = 199;

    public DownloadService(SongRepository songRepository,
                           PurchaseRepository purchaseRepository,
                           UserRepository userRepository) {
        this.songRepository = songRepository;
        this.purchaseRepository = purchaseRepository;
        this.userRepository = userRepository;
    }

    // ═══════════════════════════════════════════════════════════
    // STEP 3: Update Download Settings (called by artist)
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public Song updateDownloadSettings(UUID songId, UUID artistId,
                                       String downloadPolicy, Integer downloadPrice) {

        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new RuntimeException("Song not found: " + songId));

        // Verify the requesting user owns this song
        if (!song.getArtist().getId().equals(artistId)) {
            throw new RuntimeException("You do not own this song.");
        }

        // Validate the policy value
        if (!List.of("free", "paid", "unavailable").contains(downloadPolicy)) {
            throw new IllegalArgumentException("Invalid download policy. Must be: free, paid, or unavailable");
        }

        // Validate price logic
        if ("paid".equals(downloadPolicy)) {
            if (downloadPrice == null || downloadPrice < MINIMUM_PRICE_CENTS) {
                throw new IllegalArgumentException(
                        "Paid downloads require a minimum price of $" +
                                String.format("%.2f", MINIMUM_PRICE_CENTS / 100.0));
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
    // STEP 4A: Create a Purchase (PaymentIntent) for a paid song
    //
    // This creates a Stripe PaymentIntent using Direct Charges.
    // Money goes straight to the artist's connected Stripe account.
    // Unis takes an application fee (your 10% cut).
    //
    // Returns a client_secret that the frontend uses with Stripe.js
    // to collect the buyer's card info and confirm payment.
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> createPurchaseIntent(UUID songId, UUID buyerId) {
        Stripe.apiKey = stripeSecretKey;

        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new RuntimeException("Song not found: " + songId));

        // Validate the song is actually for sale
        if (!"paid".equals(song.getDownloadPolicy())) {
            throw new RuntimeException("This song is not available for purchase.");
        }

        // Check if buyer already owns this song
        if (purchaseRepository.existsByBuyerIdAndSongId(buyerId, songId)) {
            throw new RuntimeException("You already own this song.");
        }

        // Look up the artist's Stripe Connect account ID
        // IMPORTANT: Adjust this field name to match your User entity.
        // Your User entity should have a stripeAccountId field that was set
        // during Stripe Connect onboarding.
        User artist = userRepository.findById(song.getArtist().getId())
                .orElseThrow(() -> new RuntimeException("Artist not found"));

        String artistStripeAccountId = artist.getStripeAccountId();
        if (artistStripeAccountId == null || artistStripeAccountId.isEmpty()) {
            throw new RuntimeException("This artist has not set up payouts yet.");
        }

        int priceInCents = song.getDownloadPrice();
        int platformFee = (int) Math.round(priceInCents * PLATFORM_FEE_PERCENT);

        try {
            // Create the PaymentIntent with Direct Charge
            // The charge is created ON the artist's connected account.
            // The application_fee goes to YOUR platform account automatically.
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount((long) priceInCents)
                    .setCurrency("usd")
                    .setApplicationFeeAmount((long) platformFee)
                    .setTransferData(
                            PaymentIntentCreateParams.TransferData.builder()
                                    .setDestination(artistStripeAccountId)
                                    .build()
                    )
                    // Store metadata so we can look this up later
                    .putMetadata("song_id", songId.toString())
                    .putMetadata("buyer_id", buyerId.toString())
                    .putMetadata("artist_id", song.getArtist().getId().toString())
                    .putMetadata("type", "song_purchase")
                    .build();

            PaymentIntent paymentIntent = PaymentIntent.create(params);

            log.info("PaymentIntent created: {} for song {} buyer {} amount {}",
                    paymentIntent.getId(), songId, buyerId, priceInCents);

            // Return the client_secret to the frontend
            // The frontend uses this with Stripe.js to confirm the payment
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
    // STEP 4B: Confirm a Purchase (after Stripe.js confirms payment)
    //
    // The frontend calls this AFTER the buyer's card is charged.
    // We verify the PaymentIntent succeeded, then record the purchase
    // and return a download URL.
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> confirmPurchase(UUID songId, UUID buyerId,
                                                String paymentIntentId) {
        Stripe.apiKey = stripeSecretKey;

        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new RuntimeException("Song not found: " + songId));

        // Double-check they haven't already been recorded (idempotency)
        if (purchaseRepository.existsByBuyerIdAndSongId(buyerId, songId)) {
            // Already purchased — just return the download URL
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("downloadUrl", song.getFileUrl());
            response.put("alreadyOwned", true);
            return response;
        }

        try {
            // Verify the PaymentIntent actually succeeded with Stripe
            PaymentIntent paymentIntent = PaymentIntent.retrieve(paymentIntentId);

            if (!"succeeded".equals(paymentIntent.getStatus())) {
                throw new RuntimeException(
                        "Payment not completed. Status: " + paymentIntent.getStatus());
            }

            // Verify the metadata matches (security check — prevents someone
            // from using a PaymentIntent from a different purchase)
            String metaSongId = paymentIntent.getMetadata().get("song_id");
            String metaBuyerId = paymentIntent.getMetadata().get("buyer_id");
            if (!songId.toString().equals(metaSongId) || !buyerId.toString().equals(metaBuyerId)) {
                throw new RuntimeException("Payment verification failed — metadata mismatch.");
            }

            int amount = paymentIntent.getAmount().intValue();
            int platformFee = paymentIntent.getApplicationFeeAmount() != null
                    ? paymentIntent.getApplicationFeeAmount().intValue() : 0;

            // Record the purchase
            Purchase purchase = Purchase.builder()
                    .buyerId(buyerId)
                    .songId(songId)
                    .artistId(song.getArtist().getId())
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
    // STEP 5: Get Download URL (checks ownership)
    //
    // For free songs: returns the URL immediately.
    // For paid songs: checks if buyer has purchased, returns URL if yes.
    // For unavailable songs: returns an error.
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
    // HELPERS: Get purchase history for a user or artist
    // ═══════════════════════════════════════════════════════════

    public List<Purchase> getBuyerPurchases(UUID buyerId) {
        return purchaseRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId);
    }

    public List<Purchase> getArtistSales(UUID artistId) {
        return purchaseRepository.findByArtistIdOrderByCreatedAtDesc(artistId);
    }
}