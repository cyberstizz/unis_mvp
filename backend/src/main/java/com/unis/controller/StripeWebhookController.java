package com.unis.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.unis.service.PlusService;
import com.unis.service.SponsorService;
import com.unis.service.StripeApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Stripe → Unis notifications for Unis Plus and local sponsors.
 *
 *   POST /api/v1/stripe/webhook
 *
 * Open to the internet (Stripe can't sign in), so every delivery must carry a
 * valid Stripe-Signature made with STRIPE_WEBHOOK_SECRET; anything else gets
 * 400 and is ignored. Each handler is safe to run twice (Stripe retries, and
 * may send events out of order), so a 500 here simply means "try again".
 *
 * Events this endpoint should be subscribed to in Stripe (Workbench →
 * Webhooks → the Unis destination):
 *   checkout.session.completed      a Plus or sponsor checkout finished
 *   customer.subscription.created   Plus subscription state (created, renewed,
 *   customer.subscription.updated   card failed → past_due, canceled at the end
 *   customer.subscription.deleted   of the period, ended)
 *   invoice.paid                    a Plus month was paid (Community Share)
 *   charge.refunded                 a sponsor or Plus payment was refunded
 */
@RestController
@RequestMapping("/api/v1/stripe/webhook")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final StripeApi stripe;
    private final PlusService plus;
    private final SponsorService sponsors;

    public StripeWebhookController(StripeApi stripe, PlusService plus, SponsorService sponsors) {
        this.stripe = stripe;
        this.plus = plus;
        this.sponsors = sponsors;
    }

    @PostMapping
    public ResponseEntity<?> receive(@RequestBody(required = false) String payload,
                                     @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        if (!stripe.webhookConfigured()) {
            log.warn("[StripeWebhook] status=not_configured note=set STRIPE_WEBHOOK_SECRET");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Not configured."));
        }
        if (!stripe.verifyWebhook(payload, signature)) {
            log.warn("[StripeWebhook] status=bad_signature");
            return ResponseEntity.badRequest().body(Map.of("error", "Bad signature."));
        }

        JsonNode event = stripe.parse(payload);
        String type = event.path("type").asText("");
        String eventId = event.path("id").asText("");
        JsonNode obj = event.path("data").path("object");

        try {
            switch (type) {
                case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> {
                    String kind = obj.path("metadata").path("unis_kind").asText("");
                    if ("plus".equals(kind)) plus.onCheckoutCompleted(obj);
                    else if ("sponsor".equals(kind)) sponsors.onCheckoutCompleted(obj);
                }
                case "customer.subscription.created", "customer.subscription.updated",
                     "customer.subscription.deleted" -> plus.onSubscriptionEvent(obj);
                case "invoice.paid" -> plus.onInvoicePaid(obj);
                case "charge.refunded" -> onChargeRefunded(obj);
                default -> log.debug("[StripeWebhook] event={} type={} status=ignored", eventId, type);
            }
        } catch (Exception e) {
            // Stripe retries a failed delivery for up to three days.
            log.error("[StripeWebhook] event={} type={} status=error err={}", eventId, type, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Try again."));
        }
        log.info("[StripeWebhook] event={} type={} status=ok", eventId, type);
        return ResponseEntity.ok(Map.of("received", true));
    }

    /**
     * A refund made anywhere (an admin rejecting a sponsor, or by hand in the
     * Stripe Dashboard). Only full refunds change anything: a sponsorship is
     * marked refunded, and a Plus payment is left out of the next close. A
     * partial refund is only logged.
     */
    private void onChargeRefunded(JsonNode charge) {
        String paymentIntent = text(charge, "payment_intent");
        boolean full = charge.path("refunded").asBoolean(false);
        if (!full) {
            log.info("[StripeWebhook] charge={} status=partial_refund note=no_change", charge.path("id").asText());
            return;
        }
        sponsors.onRefunded(paymentIntent);

        // Older Stripe API versions put the invoice on the charge; newer ones
        // keep it on an InvoicePayment, looked up by the payment.
        String invoice = text(charge, "invoice");
        if (invoice == null && paymentIntent != null && text(charge, "customer") != null) {
            try {
                // payment[type]=payment_intent&payment[payment_intent]=pi_… (brackets encoded)
                JsonNode found = stripe.get("invoice_payments?payment%5Btype%5D=payment_intent"
                        + "&payment%5Bpayment_intent%5D=" + java.net.URLEncoder.encode(paymentIntent,
                                java.nio.charset.StandardCharsets.UTF_8));
                invoice = text(found.path("data").path(0), "invoice");
            } catch (Exception e) {
                log.debug("[StripeWebhook] invoice lookup skipped: {}", e.getMessage());
            }
        }
        if (invoice != null) plus.markRefunded(invoice);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isTextual()) return v.asText();
        if (v.isObject() && v.path("id").isTextual()) return v.path("id").asText();
        return null;
    }
}
