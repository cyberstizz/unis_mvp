package com.unis.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Unis Plus: the ad-free plan, $4.99 a month through Stripe Billing.
 *
 *   Subscribe   POST /api/v1/plus/checkout → Stripe's hosted checkout on Unis's
 *               own account (cards never touch Unis). The Stripe customer is
 *               created first and saved, so every webhook can be matched to
 *               the user by customer id whatever order Stripe sends them in.
 *   Plus or not A user is Plus while their subscription is active, trialing or
 *               past_due (Stripe is retrying a card) AND the paid period hasn't
 *               ended. Ad slots read GET /api/v1/plus/status and disappear.
 *   Each month  Stripe's invoice.paid webhook writes a plus_payments row. At the
 *               Community Share close, 60% of it (after Stripe's fees) goes to
 *               the artists that subscriber played that month, by counted
 *               plays; Unis's 40% joins the pot (CommunityShareService).
 *   Manage      POST /api/v1/plus/portal → Stripe's customer portal (change
 *               card, cancel). Canceling keeps Plus until the paid period
 *               ends; there are no partial refunds.
 *   Deleted     An hourly sweep cancels the subscription of any deleted
 *               account, so nobody is billed after they leave.
 *
 * Settings (Railway variables): STRIPE_PLUS_PRICE_ID (price_… of the $4.99
 * monthly price in Stripe), STRIPE_WEBHOOK_SECRET (see StripeApi).
 */
@Service
public class PlusService {

    private static final Logger log = LoggerFactory.getLogger(PlusService.class);

    /** Statuses that keep Plus on (past_due = Stripe is still retrying the card). */
    private static final Set<String> ACTIVE = Set.of("active", "trialing", "past_due");

    /** Stripe Billing's fee on subscription payments, on top of the card fee (0.7%). */
    public static final int BILLING_PER_THOUSAND = 7;

    private final JdbcTemplate jdbc;
    private final StripeApi stripe;

    @Value("${STRIPE_PLUS_PRICE_ID:}")
    private String priceId;

    @Value("${app.frontend.url:https://unisprototypetwo.netlify.app}")
    private String frontendUrl;

    /** The price, read from Stripe once and remembered (cents, e.g. 499). */
    private volatile Integer cachedPriceCents;

    public PlusService(JdbcTemplate jdbc, StripeApi stripe) {
        this.jdbc = jdbc;
        this.stripe = stripe;
    }

    public boolean configured() {
        return priceId != null && !priceId.isBlank() && stripe.webhookConfigured();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Reading
    // ═════════════════════════════════════════════════════════════════════════

    /** Is this user ad-free right now? (Guests and unknown users: no.) */
    public boolean isPlus(UUID userId) {
        if (userId == null) return false;
        // Compared with the server's clock (the dates are stored in server time).
        List<Boolean> r = jdbc.query("""
                SELECT (status IN ('active', 'trialing', 'past_due')
                        AND current_period_end IS NOT NULL AND current_period_end > ?) AS plus
                FROM plus_subscriptions WHERE user_id = ?
                """, (rs, i) -> rs.getBoolean("plus"), Timestamp.valueOf(LocalDateTime.now()), userId);
        return !r.isEmpty() && Boolean.TRUE.equals(r.get(0));
    }

    /** What the Plus page shows. */
    public Map<String, Object> status(UUID userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", configured());
        m.put("priceCents", priceCents());
        m.put("plus", false);
        m.put("status", null);
        m.put("paidThrough", null);
        m.put("cancelAtPeriodEnd", false);
        if (userId == null) return m;
        jdbc.query("SELECT status, current_period_end, cancel_at_period_end FROM plus_subscriptions WHERE user_id = ?",
                rs -> {
                    Timestamp end = rs.getTimestamp("current_period_end");
                    String status = rs.getString("status");
                    boolean plus = ACTIVE.contains(status) && end != null && end.toLocalDateTime().isAfter(LocalDateTime.now());
                    m.put("plus", plus);
                    m.put("status", status);
                    m.put("paidThrough", end == null ? null : end.toLocalDateTime().toString());
                    m.put("cancelAtPeriodEnd", rs.getBoolean("cancel_at_period_end"));
                }, userId);
        return m;
    }

    private Integer priceCents() {
        if (cachedPriceCents != null) return cachedPriceCents;
        if (priceId == null || priceId.isBlank()) return null;
        try {
            JsonNode price = stripe.get("prices/" + priceId);
            cachedPriceCents = price.path("unit_amount").asInt();
            return cachedPriceCents;
        } catch (Exception e) {
            log.warn("[Plus] action=price status=error err={}", e.getMessage());
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Checkout and the customer portal
    // ═════════════════════════════════════════════════════════════════════════

    /** Starts Stripe Checkout for the $4.99 plan. Returns the URL to send the browser to. */
    public String startCheckout(UUID userId) {
        if (!configured()) throw new IllegalStateException("Unis Plus isn't switched on yet.");
        if (isPlus(userId)) throw new IllegalStateException("You already have Unis Plus.");

        Map<String, Object> user = jdbc.queryForMap(
                "SELECT email, username, deleted_at FROM users WHERE user_id = ?", userId);
        if (user.get("deleted_at") != null) throw new IllegalStateException("This account is closed.");

        checkPrice();

        String email = (String) user.get("email");
        String username = (String) user.get("username");
        String customerId = customerFor(userId, email, username);
        try {
            return createSession(userId, customerId);
        } catch (StripeApi.StripeException e) {
            // A customer saved under another Stripe account or mode (test keys
            // swapped for live ones, or keys rotated to a new account) no longer
            // exists for the current key. Make a fresh one and try once more.
            if (e.getMessage() == null || !e.getMessage().toLowerCase().contains("no such customer")) throw e;
            log.warn("[Plus] action=checkout userId={} status=stale_customer customer={} note=recreating", userId, customerId);
            customerId = replaceCustomer(userId, email, username);
            return createSession(userId, customerId);
        }
    }

    /**
     * The Plus price must exist for the current key, be active, and repeat
     * monthly. Each mistake gets its own message, which admins see on the Plus
     * page (PlusController), so a setup problem shows up as a sentence instead
     * of "Checkout couldn't start".
     */
    private void checkPrice() {
        JsonNode price = stripe.get("prices/" + priceId);   // "No such price" comes back as a StripeException
        if (!price.path("active").asBoolean(true)) {
            throw new StripeApi.StripeException(0, "The Plus price (" + priceId + ") is archived in Stripe. "
                    + "Make it active again, or set STRIPE_PLUS_PRICE_ID to an active price.");
        }
        if (!"recurring".equals(price.path("type").asText())) {
            throw new StripeApi.StripeException(0, "The Plus price (" + priceId + ") is a one-time price. "
                    + "Unis Plus needs a recurring monthly price.");
        }
        cachedPriceCents = price.path("unit_amount").asInt();
    }

    private String createSession(UUID userId, String customerId) {
        Map<String, String> f = StripeApi.form();
        f.put("mode", "subscription");
        f.put("customer", customerId);
        f.put("client_reference_id", userId.toString());
        f.put("line_items[0][price]", priceId);
        f.put("line_items[0][quantity]", "1");
        f.put("subscription_data[metadata][unis_user_id]", userId.toString());
        f.put("metadata[unis_kind]", "plus");
        f.put("metadata[unis_user_id]", userId.toString());
        f.put("success_url", frontendUrl + "/plus?status=success");
        f.put("cancel_url", frontendUrl + "/plus?status=canceled");
        JsonNode session = stripe.post("checkout/sessions", f);
        log.info("[Plus] action=checkout userId={} session={}", userId, session.path("id").asText());
        return session.path("url").asText();
    }

    /** Swaps a stale Stripe customer for a new one (only while the user has no live subscription). */
    private String replaceCustomer(UUID userId, String email, String username) {
        Map<String, String> f = StripeApi.form();
        if (email != null && !email.isBlank()) f.put("email", email);
        if (username != null) f.put("name", username);
        f.put("metadata[unis_user_id]", userId.toString());
        JsonNode customer = stripe.post("customers", f, "unis-plus-customer-" + userId + "-" + UUID.randomUUID());
        String customerId = customer.path("id").asText();
        jdbc.update("""
                UPDATE plus_subscriptions
                   SET stripe_customer_id = ?, stripe_subscription_id = NULL, status = 'incomplete',
                       current_period_end = NULL, cancel_at_period_end = FALSE, updated_at = NOW()
                 WHERE user_id = ?
                """, customerId, userId);
        log.info("[Plus] action=replace_customer userId={} customer={}", userId, customerId);
        return customerId;
    }

    /** Opens Stripe's customer portal (change card, cancel). */
    public String portal(UUID userId) {
        List<String> ids = jdbc.queryForList(
                "SELECT stripe_customer_id FROM plus_subscriptions WHERE user_id = ?", String.class, userId);
        if (ids.isEmpty()) throw new IllegalStateException("You don't have Unis Plus yet.");
        Map<String, String> f = StripeApi.form();
        f.put("customer", ids.get(0));
        f.put("return_url", frontendUrl + "/plus");
        JsonNode session = stripe.post("billing_portal/sessions", f);
        return session.path("url").asText();
    }

    /** The user's Stripe customer, created once and remembered. */
    private String customerFor(UUID userId, String email, String username) {
        List<String> ids = jdbc.queryForList(
                "SELECT stripe_customer_id FROM plus_subscriptions WHERE user_id = ?", String.class, userId);
        if (!ids.isEmpty()) return ids.get(0);
        Map<String, String> f = StripeApi.form();
        if (email != null && !email.isBlank()) f.put("email", email);
        if (username != null) f.put("name", username);
        f.put("metadata[unis_user_id]", userId.toString());
        JsonNode customer = stripe.post("customers", f, "unis-plus-customer-" + userId);
        String customerId = customer.path("id").asText();
        jdbc.update("""
                INSERT INTO plus_subscriptions (user_id, stripe_customer_id, status)
                VALUES (?, ?, 'incomplete')
                ON CONFLICT (user_id) DO NOTHING
                """, userId, customerId);
        return jdbc.queryForObject("SELECT stripe_customer_id FROM plus_subscriptions WHERE user_id = ?",
                String.class, userId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Webhooks (called by StripeWebhookController after the signature check)
    // ═════════════════════════════════════════════════════════════════════════

    /** checkout.session.completed for a Plus checkout: load the new subscription. */
    public void onCheckoutCompleted(JsonNode session) {
        String subscriptionId = text(session, "subscription");
        if (subscriptionId != null) refreshSubscription(subscriptionId);
    }

    /**
     * customer.subscription.created / updated / deleted. Stripe doesn't promise
     * event order, so the subscription is re-read from Stripe rather than taken
     * from the event, and the newest state always wins.
     */
    public void onSubscriptionEvent(JsonNode subscription) {
        String id = text(subscription, "id");
        if (id != null) refreshSubscription(id);
    }

    private void refreshSubscription(String subscriptionId) {
        JsonNode sub = stripe.get("subscriptions/" + subscriptionId);
        String customerId = text(sub, "customer");
        if (customerId == null) return;
        String status = sub.path("status").asText("incomplete");
        // Newer API versions keep the period on the subscription item.
        long periodEnd = sub.path("current_period_end").asLong(0);
        if (periodEnd == 0) periodEnd = sub.path("items").path("data").path(0).path("current_period_end").asLong(0);
        // Canceled in the portal: classic subscriptions set cancel_at_period_end,
        // "flexible" billing-mode ones set cancel_at instead.
        boolean cancelAtEnd = sub.path("cancel_at_period_end").asBoolean(false)
                || sub.path("cancel_at").asLong(0) > 0;

        int updated = jdbc.update("""
                UPDATE plus_subscriptions
                   SET stripe_subscription_id = ?, status = ?, current_period_end = ?,
                       cancel_at_period_end = ?, updated_at = NOW()
                 WHERE stripe_customer_id = ?
                """, subscriptionId, status, periodEnd == 0 ? null : toServerTime(periodEnd), cancelAtEnd, customerId);
        if (updated == 0) {
            log.warn("[Plus] action=subscription status=unknown_customer customer={} sub={}", customerId, subscriptionId);
            return;
        }
        log.info("[Plus] action=subscription sub={} status={} cancelAtEnd={}", subscriptionId, status, cancelAtEnd);
    }

    /**
     * invoice.paid: record the payment once (the invoice id is the key) and
     * extend the paid-through date right away, so Plus turns on even if the
     * subscription webhook arrives later.
     */
    public void onInvoicePaid(JsonNode invoice) {
        String invoiceId = text(invoice, "id");
        String customerId = text(invoice, "customer");
        int amountPaid = invoice.path("amount_paid").asInt(0);
        if (invoiceId == null || customerId == null || amountPaid <= 0) return;

        List<UUID> users = jdbc.queryForList(
                "SELECT user_id FROM plus_subscriptions WHERE stripe_customer_id = ?", UUID.class, customerId);
        if (users.isEmpty()) {
            // Not a Plus customer (or a sponsor). Nothing to do.
            log.info("[Plus] action=invoice status=not_plus invoice={}", invoiceId);
            return;
        }
        UUID userId = users.get(0);

        JsonNode line = invoice.path("lines").path("data").path(0);
        long start = line.path("period").path("start").asLong(0);
        long end = line.path("period").path("end").asLong(0);
        long paidAt = invoice.path("status_transitions").path("paid_at").asLong(Instant.now().getEpochSecond());

        int cardFee = PlatformFees.stripeCardFeeCents(amountPaid);
        int billingFee = (amountPaid * BILLING_PER_THOUSAND + 999) / 1000;
        int fees = Math.min(amountPaid, cardFee + billingFee);

        List<UUID> artist = jdbc.queryForList(
                "SELECT supported_artist_id FROM users WHERE user_id = ? AND supported_artist_id IS NOT NULL",
                UUID.class, userId);

        int inserted = jdbc.update("""
                INSERT INTO plus_payments
                    (invoice_id, user_id, supported_artist_id, amount_paid, stripe_fees, net_amount,
                     period_start, period_end, paid_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (invoice_id) DO NOTHING
                """, invoiceId, userId, artist.isEmpty() ? null : artist.get(0), amountPaid, fees,
                amountPaid - fees, start == 0 ? null : toServerTime(start), end == 0 ? null : toServerTime(end),
                toServerTime(paidAt));

        if (end > 0) {
            jdbc.update("""
                    UPDATE plus_subscriptions
                       SET current_period_end = GREATEST(COALESCE(current_period_end, ?), ?),
                           status = CASE WHEN status IN ('incomplete', 'incomplete_expired', 'past_due', 'unpaid')
                                         THEN 'active' ELSE status END,
                           updated_at = NOW()
                     WHERE user_id = ?
                    """, toServerTime(end), toServerTime(end), userId);
        }
        log.info("[Plus] action=payment invoice={} userId={} amount={} fees={} new={}",
                invoiceId, userId, amountPaid, fees, inserted == 1);
    }

    /** A Plus payment refunded in Stripe: leave it out of any close that hasn't run yet. */
    public void markRefunded(String invoiceId) {
        int n = jdbc.update("UPDATE plus_payments SET status = 'refunded' WHERE invoice_id = ?", invoiceId);
        if (n > 0) {
            Integer settled = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM plus_payments WHERE invoice_id = ? AND settled_close_id IS NOT NULL",
                    Integer.class, invoiceId);
            if (settled != null && settled > 0) {
                log.warn("[Plus] action=refund invoice={} status=already_paid_out note=adjust_artists_by_hand", invoiceId);
            } else {
                log.info("[Plus] action=refund invoice={} status=ok", invoiceId);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Housekeeping
    // ═════════════════════════════════════════════════════════════════════════

    /** Every hour: cancel the subscription of any account that has been deleted. */
    @Scheduled(cron = "0 17 * * * *", zone = "America/New_York")
    public void cancelForDeletedAccounts() {
        if (priceId == null || priceId.isBlank()) return;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.user_id, p.stripe_subscription_id FROM plus_subscriptions p
                JOIN users u ON u.user_id = p.user_id
                WHERE u.deleted_at IS NOT NULL AND p.stripe_subscription_id IS NOT NULL
                  AND p.status NOT IN ('canceled', 'incomplete_expired')
                """);
        for (Map<String, Object> r : rows) {
            String sub = (String) r.get("stripe_subscription_id");
            try {
                stripe.delete("subscriptions/" + sub);
                jdbc.update("UPDATE plus_subscriptions SET status = 'canceled', updated_at = NOW() WHERE user_id = ?",
                        r.get("user_id"));
                log.info("[Plus] action=cancel_deleted_account sub={} status=ok", sub);
            } catch (Exception e) {
                log.warn("[Plus] action=cancel_deleted_account sub={} status=error err={}", sub, e.getMessage());
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** A field that may be a plain id or an expanded object with an id. */
    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isTextual()) return v.asText();
        if (v.isObject() && v.path("id").isTextual()) return v.path("id").asText();
        return null;
    }

    /** Stripe sends Unix seconds; the database keeps server-local time, like the rest of Unis. */
    static Timestamp toServerTime(long epochSeconds) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault()));
    }
}
