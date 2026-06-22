package com.unis.service;

public class DirectSupportService {

}
package com.unis.service;

import com.stripe.Stripe;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.unis.entity.SupportPayment;
import com.unis.entity.User;
import com.unis.repository.SupportPaymentRepository;
import com.unis.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Direct fan-to-artist support ("tips").
 *
 * This intentionally mirrors {@link DownloadService}'s purchase flow: a Stripe
 * destination charge ({@code transfer_data.destination}) routes the money
 * straight to the artist's connected account, Unis keeps an application fee,
 * and the result is recorded as a {@link SupportPayment}. Because it rides the
 * same Connect rails, support shows up in the artist's Stripe Express dashboard
 * and pays out exactly like a song sale — no new payout logic required.
 */
@Service
@Slf4j
public class DirectSupportService {

    private final SupportPaymentRepository supportPaymentRepository;
    private final UserRepository userRepository;

    @Value("${stripe.secret.key}")
    private String stripeSecretKey;

    // Unis cut on direct support. Kept at 10% to match song purchases for
    // consistency. Lower this (even to 0.0) to make direct support more
    // artist-friendly than the competition — it only changes the fee, nothing
    // downstream depends on the value.
    private static final double PLATFORM_FEE_PERCENT = 0.10;

    // Minimum support amount in cents ($1.00) so name-your-price stays low.
    // NOTE: on very small amounts the 10% application fee may not fully cover
    // Stripe's per-transaction processing (~2.9% + $0.30), which on a
    // destination charge is billed to the platform account. If you want the
    // platform to never run negative on tiny tips, either raise this floor or
    // set on_behalf_of on the PaymentIntent so processing fees come out of the
    // connected account instead.
    private static final int MINIMUM_SUPPORT_CENTS = 100;

    // Hard ceiling to blunt fat-finger / fraud ($2,000). Adjust as needed.
    private static final int MAXIMUM_SUPPORT_CENTS = 200_000;

    private static final int NOTE_MAX_LEN = 280;

    public DirectSupportService(SupportPaymentRepository supportPaymentRepository,
                                UserRepository userRepository) {
        this.supportPaymentRepository = supportPaymentRepository;
        this.userRepository = userRepository;
    }

    // ═══════════════════════════════════════════════════════════
    // CREATE SUPPORT INTENT (Stripe destination charge)
    //
    // Returns client_secret for Stripe.js on the frontend.
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> createSupportIntent(UUID artistId, UUID supporterId,
                                                    int amountCents, String note, String source) {
        Stripe.apiKey = stripeSecretKey;

        if (artistId.equals(supporterId)) {
            throw new RuntimeException("You can't send support to yourself.");
        }
        if (amountCents < MINIMUM_SUPPORT_CENTS) {
            throw new RuntimeException("Minimum support is $"
                    + String.format("%.2f", MINIMUM_SUPPORT_CENTS / 100.0) + ".");
        }
        if (amountCents > MAXIMUM_SUPPORT_CENTS) {
            throw new RuntimeException("Maximum support is $"
                    + String.format("%.2f", MAXIMUM_SUPPORT_CENTS / 100.0) + ".");
        }

        User artist = userRepository.findById(artistId)
                .orElseThrow(() -> new RuntimeException("Artist not found."));

        String artistStripeAccountId = artist.getStripeAccountId();
        if (artistStripeAccountId == null || artistStripeAccountId.isEmpty()
                || !Boolean.TRUE.equals(artist.getStripeOnboardingComplete())) {
            throw new RuntimeException(
                    "This artist hasn't finished setting up payouts yet, so they can't "
                            + "receive support right now.");
        }

        String cleanNote = sanitizeNote(note);
        String cleanSource = (source != null && source.equalsIgnoreCase("dm")) ? "dm" : "profile";
        int platformFee = (int) Math.round(amountCents * PLATFORM_FEE_PERCENT);

        try {
            PaymentIntentCreateParams.Builder params = PaymentIntentCreateParams.builder()
                    .setAmount((long) amountCents)
                    .setCurrency("usd")
                    .setApplicationFeeAmount((long) platformFee)
                    .setTransferData(
                            PaymentIntentCreateParams.TransferData.builder()
                                    .setDestination(artistStripeAccountId)
                                    .build()
                    )
                    .putMetadata("supporter_id", supporterId.toString())
                    .putMetadata("artist_id", artistId.toString())
                    .putMetadata("type", "direct_support")
                    .putMetadata("source", cleanSource);

            if (cleanNote != null) {
                params.putMetadata("note", cleanNote);
            }

            PaymentIntent paymentIntent = PaymentIntent.create(params.build());

            log.info("Support PaymentIntent created: {} supporter {} -> artist {} amount {}c",
                    paymentIntent.getId(), supporterId, artistId, amountCents);

            Map<String, Object> response = new HashMap<>();
            response.put("clientSecret", paymentIntent.getClientSecret());
            response.put("paymentIntentId", paymentIntent.getId());
            response.put("amount", amountCents);
            response.put("platformFee", platformFee);
            response.put("artistReceives", amountCents - platformFee);
            return response;

        } catch (Exception e) {
            log.error("Failed to create support PaymentIntent for artist {}: {}",
                    artistId, e.getMessage());
            throw new RuntimeException("Payment processing failed. Please try again.");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CONFIRM SUPPORT (after Stripe.js confirms the payment)
    //
    // Verifies the PaymentIntent succeeded + metadata matches, then records
    // the SupportPayment. Note/source are read back from the intent so the
    // confirm body only needs the paymentIntentId — same shape as confirmPurchase.
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> confirmSupport(UUID artistId, UUID supporterId,
                                              String paymentIntentId) {
        Stripe.apiKey = stripeSecretKey;

        // Idempotency — if we already recorded this intent, return it.
        var existing = supportPaymentRepository.findByStripePaymentIntentId(paymentIntentId);
        if (existing.isPresent()) {
            SupportPayment sp = existing.get();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("supportId", sp.getId());
            response.put("amount", sp.getAmount());
            response.put("alreadyRecorded", true);
            return response;
        }

        try {
            PaymentIntent paymentIntent = PaymentIntent.retrieve(paymentIntentId);

            if (!"succeeded".equals(paymentIntent.getStatus())) {
                throw new RuntimeException(
                        "Payment not completed. Status: " + paymentIntent.getStatus());
            }

            Map<String, String> meta = paymentIntent.getMetadata();
            if (!"direct_support".equals(meta.get("type"))
                    || !supporterId.toString().equals(meta.get("supporter_id"))
                    || !artistId.toString().equals(meta.get("artist_id"))) {
                throw new RuntimeException("Payment verification failed — metadata mismatch.");
            }

            int amount = paymentIntent.getAmount().intValue();
            int platformFee = paymentIntent.getApplicationFeeAmount() != null
                    ? paymentIntent.getApplicationFeeAmount().intValue() : 0;
            String note = meta.get("note");
            String source = meta.getOrDefault("source", "profile");

            SupportPayment support = SupportPayment.builder()
                    .supporterId(supporterId)
                    .artistId(artistId)
                    .amount(amount)
                    .platformFee(platformFee)
                    .note(note)
                    .source(source)
                    .stripePaymentIntentId(paymentIntentId)
                    .status("completed")
                    .build();

            support = supportPaymentRepository.save(support);

            log.info("Support recorded: supporter {} -> artist {} for {}c",
                    supporterId, artistId, amount);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("supportId", support.getId());
            response.put("amount", amount);
            return response;

        } catch (Exception e) {
            log.error("Support confirmation failed (artist {}): {}", artistId, e.getMessage());
            throw new RuntimeException("Support confirmation failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // HISTORY + STATS
    // ═══════════════════════════════════════════════════════════

    public List<SupportPayment> getReceivedSupport(UUID artistId) {
        return supportPaymentRepository.findByArtistIdOrderByCreatedAtDesc(artistId);
    }

    public List<SupportPayment> getSentSupport(UUID supporterId) {
        return supportPaymentRepository.findBySupporterIdOrderByCreatedAtDesc(supporterId);
    }

    /**
     * Public-facing rollup for an artist's profile: lifetime raised, this
     * month, and distinct supporter count. Amounts returned in dollars.
     */
    public Map<String, Object> getArtistSupportStats(UUID artistId) {
        long lifetimeCents = supportPaymentRepository.sumReceivedCents(artistId);
        LocalDateTime monthStart = LocalDateTime.now()
                .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        long monthCents = supportPaymentRepository.sumReceivedCentsSince(artistId, monthStart);
        long supporters = supportPaymentRepository.countDistinctSupporters(artistId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("lifetimeRaised", lifetimeCents / 100.0);
        stats.put("thisMonthRaised", monthCents / 100.0);
        stats.put("supporterCount", supporters);
        return stats;
    }

    // ═══════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════

    private String sanitizeNote(String note) {
        if (note == null) return null;
        String trimmed = note.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > NOTE_MAX_LEN ? trimmed.substring(0, NOTE_MAX_LEN) : trimmed;
    }
}