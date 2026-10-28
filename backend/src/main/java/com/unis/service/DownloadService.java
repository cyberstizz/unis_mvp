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
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbc;

    public DownloadService(SongRepository songRepository,
                           PurchaseRepository purchaseRepository,
                           UserRepository userRepository,
                           JdbcTemplate jdbc) {
        this.songRepository = songRepository;
        this.purchaseRepository = purchaseRepository;
        this.userRepository = userRepository;
        this.jdbc = jdbc;
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

        UUID artistUserId = song.getArtist().getUserId();
        // Buying your own song moves no music, only money: it's how stolen cards
        // get cashed out through a store, so it's never allowed.
        if (artistUserId.equals(buyerId)) {
            throw new RuntimeException("You can't buy your own song. You can always download it from your dashboard.");
        }
        User artist = userRepository.findById(artistUserId)
                .orElseThrow(() -> new RuntimeException("Artist not found"));

        String artistStripeAccountId = artist.getStripeAccountId();
        if (artistStripeAccountId == null || artistStripeAccountId.isEmpty()
                || !Boolean.TRUE.equals(artist.getStripeOnboardingComplete())) {
            throw new RuntimeException(
                    "This artist hasn't finished setting up payouts yet, so this song can't be bought right now.");
        }
        String buyerEmail = userRepository.findById(buyerId).map(User::getEmail).orElse(null);

        int priceInCents = song.getDownloadPrice();
        // Break-even gate: never below Stripe's card fee + 1¢ (songs priced
        // before the $0.94 minimum existed are covered the same way).
        int platformFee = PlatformFees.feeCents(priceInCents, PLATFORM_FEE_PERCENT);

        try {
            PaymentIntentCreateParams.Builder params = PaymentIntentCreateParams.builder()
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
                    .putMetadata("type", "song_purchase");
            if (buyerEmail != null && !buyerEmail.isBlank()) {
                params.setReceiptEmail(buyerEmail);   // Stripe emails the receipt
            }

            PaymentIntent paymentIntent = PaymentIntent.create(params.build());

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

            Long purchaseId = recordPurchase(buyerId, songId, song.getArtist().getUserId(),
                    amount, platformFee, paymentIntentId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("downloadUrl", song.getFileUrl());
            response.put("purchaseId", purchaseId);
            return response;

        } catch (Exception e) {
            log.error("Purchase confirmation failed for song {}: {}", songId, e.getMessage());
            throw new RuntimeException("Purchase confirmation failed: " + e.getMessage());
        }
    }

    /**
     * payment_intent.succeeded for a song (StripeWebhookController). Records the
     * purchase even if the buyer closed the page before the app confirmed it,
     * so they still own the song. Safe to run twice and alongside
     * confirmPurchase: one row per PaymentIntent. A song removed in the
     * meantime is refunded in full, the same as confirmPurchase does.
     */
    @Transactional
    public void recordFromWebhook(JsonNode pi) {
        Stripe.apiKey = stripeSecretKey;
        JsonNode meta = pi.path("metadata");
        String paymentIntentId = pi.path("id").asText(null);
        String songId = meta.path("song_id").asText(null);
        String buyerId = meta.path("buyer_id").asText(null);
        String artistId = meta.path("artist_id").asText(null);
        if (paymentIntentId == null || songId == null || buyerId == null || artistId == null) {
            log.warn("[Purchase] action=webhook intent={} status=missing_metadata", paymentIntentId);
            return;
        }
        Song song = songRepository.findById(UUID.fromString(songId)).orElse(null);
        if (song != null && song.isRemoved()) {
            refundRemovedSongPurchase(song, UUID.fromString(buyerId), paymentIntentId);
            return;
        }
        int amount = pi.path("amount_received").asInt(pi.path("amount").asInt(0));
        int fee = pi.path("application_fee_amount").asInt(0);
        recordPurchase(UUID.fromString(buyerId), UUID.fromString(songId), UUID.fromString(artistId),
                amount, fee, paymentIntentId);
    }

    /** One row per PaymentIntent (unique index), whoever gets here first. Returns the row's id. */
    private Long recordPurchase(UUID buyerId, UUID songId, UUID artistId, int amount, int platformFee,
                                String paymentIntentId) {
        List<Long> inserted = jdbc.queryForList("""
                INSERT INTO purchases
                    (buyer_id, song_id, artist_id, amount, platform_fee, stripe_payment_intent_id, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'completed', NOW())
                ON CONFLICT (stripe_payment_intent_id) DO NOTHING
                RETURNING id
                """, Long.class, buyerId, songId, artistId, amount, platformFee, paymentIntentId);
        if (!inserted.isEmpty()) {
            log.info("[Purchase] action=record buyer={} song={} amount={}c intent={} status=ok",
                    buyerId, songId, amount, paymentIntentId);
            return inserted.get(0);
        }
        return jdbc.queryForObject("SELECT id FROM purchases WHERE stripe_payment_intent_id = ?",
                Long.class, paymentIntentId);
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