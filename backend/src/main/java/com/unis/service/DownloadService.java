package com.unis.service;

import com.stripe.Stripe;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
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

    // Unis's cut: 10%, or Stripe's card fee + 1¢ when that's higher, so Unis
    // never loses money on a sale (see PlatformFees for the table).
    private static final double PLATFORM_FEE_PERCENT = PlatformFees.DEFAULT_PERCENT;

    // Minimum price in cents ($0.94): 5¢ under the iTunes Store's 99¢ tier, so a
    // song can always be cheapest on Unis. The fee gate keeps Unis whole at it.
    private static final int MINIMUM_PRICE_CENTS = PlatformFees.MIN_SONG_PRICE_CENTS;

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
    // Unis takes application_fee (10%, or Stripe's card fee + 1¢ if higher).
    // Returns client_secret for Stripe.js on the frontend.
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> createPurchaseIntent(UUID songId, UUID buyerId) {
        Stripe.apiKey = stripeSecretKey;

        Song song = songRepository.findById(songId)
                .orElseThrow(() -> new RuntimeException("Song not found: " + songId));
        if (song.isRemoved() || (song.getArtist() != null && song.getArtist().getDeletedAt() != null)) {
            throw new RuntimeException("This song was removed and is no longer for sale.");
        }

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
        // Break-even gate: never below Stripe's card fee + 1¢ (songs priced
        // before the $0.94 minimum existed are covered the same way).
        int platformFee = PlatformFees.feeCents(priceInCents, PLATFORM_FEE_PERCENT);

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

        // The song was removed after checkout started: never charge for a file
        // nobody can download. Refund in full (the artist's share and Unis's fee
        // both come back) and record it as refunded.
        if (song.isRemoved()) {
            return refundRemovedSongPurchase(song, buyerId, paymentIntentId);
        }

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

        // A removed song can't be downloaded or bought by anyone, including
        // past buyers (they got the file when they bought it).
        if (song.isRemoved()) {
            response.put("downloadPolicy", "unavailable");
            response.put("canDownload", false);
            response.put("removed", true);
            return response;
        }

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

    /**
     * A buyer paid for a song that was removed before the purchase was
     * confirmed. Verifies the payment is really theirs, refunds it in full
     * (reverse_transfer pulls the artist's share back from their Stripe
     * account; refund_application_fee returns Unis's fee), and records the
     * purchase as refunded. The idempotency key makes a retried confirm return
     * the same refund instead of failing.
     */
    private Map<String, Object> refundRemovedSongPurchase(Song song, UUID buyerId, String paymentIntentId) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("refunded", true);
        response.put("message", "This song was removed before your purchase finished. "
                + "You've been refunded in full.");

        // Already handled on an earlier attempt.
        var existing = purchaseRepository.findByStripePaymentIntentId(paymentIntentId);
        if (existing.isPresent()) {
            return response;
        }

        try {
            PaymentIntent paymentIntent = PaymentIntent.retrieve(paymentIntentId);
            String metaSongId = paymentIntent.getMetadata().get("song_id");
            String metaBuyerId = paymentIntent.getMetadata().get("buyer_id");
            if (!song.getSongId().toString().equals(metaSongId) || !buyerId.toString().equals(metaBuyerId)) {
                throw new RuntimeException("Payment verification failed — metadata mismatch.");
            }
            if (!"succeeded".equals(paymentIntent.getStatus())) {
                // Nothing was charged, so there's nothing to refund.
                response.put("refunded", false);
                response.put("message", "This song was removed and is no longer for sale. You were not charged.");
                return response;
            }

            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(paymentIntentId)
                    .setReverseTransfer(true)
                    .setRefundApplicationFee(true)
                    .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                    .putMetadata("unis_reason", "song_removed_before_confirm")
                    .build();
            RequestOptions options = RequestOptions.builder()
                    .setIdempotencyKey("unis-refund-removed-" + paymentIntentId)
                    .build();
            Refund refund = Refund.create(params, options);

            int amount = paymentIntent.getAmount().intValue();
            int platformFee = paymentIntent.getApplicationFeeAmount() != null
                    ? paymentIntent.getApplicationFeeAmount().intValue() : 0;
            purchaseRepository.save(Purchase.builder()
                    .buyerId(buyerId)
                    .songId(song.getSongId())
                    .artistId(song.getArtist().getUserId())
                    .amount(amount)
                    .platformFee(platformFee)
                    .stripePaymentIntentId(paymentIntentId)
                    .status("refunded")
                    .build());

            log.info("Refunded purchase of removed song {} for buyer {}: refund {} ({} cents)",
                    song.getSongId(), buyerId, refund.getId(), amount);
            return response;
        } catch (Exception e) {
            log.error("Refund for removed song {} (intent {}) failed: {}",
                    song.getSongId(), paymentIntentId, e.getMessage());
            throw new RuntimeException("This song was removed before your purchase finished. "
                    + "We couldn't refund you automatically — please contact support with this reference: "
                    + paymentIntentId);
        }
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