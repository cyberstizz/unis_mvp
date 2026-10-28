package com.unis.service;

import com.stripe.Stripe;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.unis.entity.SupportPayment;
import com.unis.entity.User;
import com.unis.repository.SupportPaymentRepository;
import com.unis.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

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

    // Unis cut on direct support: 10%, matching song purchases, or Stripe's
    // card fee + 1¢ when that's higher (PlatformFees). On a destination charge
    // Stripe bills its card fee to Unis, so without the gate a $1 tip would
    // cost Unis 23¢. Setting this to 0.0 makes every tip exactly break-even
    // + 1¢ for Unis.
    private static final double PLATFORM_FEE_PERCENT = PlatformFees.DEFAULT_PERCENT;

    // Minimum support amount in cents ($1.00) so name-your-price stays low.
    // At $1.00 the gate takes 34¢ (Stripe's 33¢ + 1¢); the artist gets 66¢.
    private static final int MINIMUM_SUPPORT_CENTS = 100;

    // Most one payment can be ($100), against fat fingers and fraud.
    private static final int MAXIMUM_SUPPORT_CENTS = 10_000;

    // Money in DMs and on profiles: fan to artist only, with limits, so Unis
    // can't be used to move money between people (Stripe prohibits
    // person-to-person money transmission) or to cash out stolen cards.
    private static final int DAILY_LIMIT_CENTS = 25_000;    // $250 per sender in any 24 hours
    private static final int WEEKLY_LIMIT_CENTS = 50_000;   // $500 per sender in any 7 days
    private static final int MIN_ACCOUNT_AGE_DAYS = 7;      // a brand-new account can't send money yet

    // A note is a message to the artist, not a way to arrange payment elsewhere.
    private static final Pattern NOTE_BLOCKED = Pattern.compile(
            "(https?://|www\\.|\\b[a-z0-9-]+\\.(com|net|org|io|me|app|co|ly|gg)\\b"
            + "|\\$[a-z][a-z0-9_]{1,}|\\b(venmo|zelle|cash ?app|paypal|telegram|whatsapp|bitcoin|btc|usdt)\\b"
            + "|(\\d[\\s().-]*){7,})",
            Pattern.CASE_INSENSITIVE);

    private static final int NOTE_MAX_LEN = 280;

    private final JdbcTemplate jdbc;

    public DirectSupportService(SupportPaymentRepository supportPaymentRepository,
                                UserRepository userRepository,
                                JdbcTemplate jdbc) {
        this.supportPaymentRepository = supportPaymentRepository;
        this.userRepository = userRepository;
        this.jdbc = jdbc;
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
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new RuntimeException("This artist is no longer on Unis."));
        if (artist.getRole() != User.Role.artist) {
            throw new RuntimeException("Support can only be sent to artists.");
        }

        User supporter = userRepository.findById(supporterId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new RuntimeException("Your account can't send support."));
        if (!Boolean.TRUE.equals(supporter.getPhoneVerified())) {
            throw new RuntimeException("Verify your phone number to send support.");
        }
        if (supporter.getCreatedAt() != null
                && supporter.getCreatedAt().isAfter(LocalDateTime.now().minusDays(MIN_ACCOUNT_AGE_DAYS))) {
            throw new RuntimeException("New accounts can send support after their first " + MIN_ACCOUNT_AGE_DAYS + " days.");
        }
        checkSenderLimits(supporterId, amountCents);

        String artistStripeAccountId = artist.getStripeAccountId();
        if (artistStripeAccountId == null || artistStripeAccountId.isEmpty()
                || !Boolean.TRUE.equals(artist.getStripeOnboardingComplete())) {
            throw new RuntimeException(
                    "This artist hasn't finished setting up payouts yet, so they can't "
                            + "receive support right now.");
        }

        String cleanNote = sanitizeNote(note);
        if (cleanNote != null && NOTE_BLOCKED.matcher(cleanNote).find()) {
            throw new RuntimeException("Notes can't include links, phone numbers or payment handles.");
        }
        String cleanSource = (source != null && source.equalsIgnoreCase("dm")) ? "dm" : "profile";
        int platformFee = PlatformFees.feeCents(amountCents, PLATFORM_FEE_PERCENT);

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
            if (supporter.getEmail() != null && !supporter.getEmail().isBlank()) {
                params.setReceiptEmail(supporter.getEmail());   // Stripe emails the receipt
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

            Long supportId = recordSupport(supporterId, artistId, amount, platformFee, note, source, paymentIntentId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("supportId", supportId);
            response.put("amount", amount);
            return response;

        } catch (Exception e) {
            log.error("Support confirmation failed (artist {}): {}", artistId, e.getMessage());
            throw new RuntimeException("Support confirmation failed: " + e.getMessage());
        }
    }

    /**
     * payment_intent.succeeded for a support payment (StripeWebhookController).
     * Records it even if the fan closed the page before the app confirmed it.
     * Safe to run twice and alongside confirmSupport: one row per PaymentIntent.
     */
    public void recordFromWebhook(JsonNode pi) {
        JsonNode meta = pi.path("metadata");
        String paymentIntentId = pi.path("id").asText(null);
        String supporter = meta.path("supporter_id").asText(null);
        String artist = meta.path("artist_id").asText(null);
        if (paymentIntentId == null || supporter == null || artist == null) {
            log.warn("[Support] action=webhook intent={} status=missing_metadata", paymentIntentId);
            return;
        }
        int amount = pi.path("amount_received").asInt(pi.path("amount").asInt(0));
        int fee = pi.path("application_fee_amount").asInt(0);
        String note = meta.hasNonNull("note") ? meta.path("note").asText() : null;
        String source = meta.path("source").asText("profile");
        recordSupport(UUID.fromString(supporter), UUID.fromString(artist), amount, fee, note, source, paymentIntentId);
    }

    /** One row per PaymentIntent (unique index), whoever gets here first. Returns the row's id. */
    private Long recordSupport(UUID supporterId, UUID artistId, int amount, int platformFee,
                               String note, String source, String paymentIntentId) {
        List<Long> inserted = jdbc.queryForList("""
                INSERT INTO support_payments
                    (supporter_id, artist_id, amount, platform_fee, note, source,
                     stripe_payment_intent_id, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'completed', NOW())
                ON CONFLICT (stripe_payment_intent_id) DO NOTHING
                RETURNING id
                """, Long.class, supporterId, artistId, amount, platformFee, note,
                "dm".equals(source) ? "dm" : "profile", paymentIntentId);
        if (!inserted.isEmpty()) {
            log.info("[Support] action=record supporter={} artist={} amount={}c intent={} status=ok",
                    supporterId, artistId, amount, paymentIntentId);
            return inserted.get(0);
        }
        return jdbc.queryForObject("SELECT id FROM support_payments WHERE stripe_payment_intent_id = ?",
                Long.class, paymentIntentId);
    }

    /** $250 in any 24 hours and $500 in any 7 days per sender, across all artists. */
    private void checkSenderLimits(UUID supporterId, int amountCents) {
        Map<String, Object> sent = jdbc.queryForMap("""
                SELECT COALESCE(SUM(amount) FILTER (WHERE created_at > NOW() - INTERVAL '24 hours'), 0) AS day,
                       COALESCE(SUM(amount), 0) AS week
                FROM support_payments
                WHERE supporter_id = ? AND status = 'completed' AND created_at > NOW() - INTERVAL '7 days'
                """, supporterId);
        long day = ((Number) sent.get("day")).longValue();
        long week = ((Number) sent.get("week")).longValue();
        if (day + amountCents > DAILY_LIMIT_CENTS) {
            throw new RuntimeException("You can send up to $" + (DAILY_LIMIT_CENTS / 100) + " in support a day. "
                    + "You have $" + String.format("%.2f", Math.max(0, DAILY_LIMIT_CENTS - day) / 100.0) + " left today.");
        }
        if (week + amountCents > WEEKLY_LIMIT_CENTS) {
            throw new RuntimeException("You can send up to $" + (WEEKLY_LIMIT_CENTS / 100) + " in support a week. "
                    + "You have $" + String.format("%.2f", Math.max(0, WEEKLY_LIMIT_CENTS - week) / 100.0) + " left this week.");
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