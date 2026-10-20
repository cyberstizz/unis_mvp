package com.unis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * A small client for the Stripe calls Unis Plus and sponsors make (Checkout,
 * the customer portal, subscriptions, refunds), plus webhook signature checks.
 *
 * Plain HTTPS with form bodies, the same way StripeConnectService talks to
 * Stripe, so it doesn't depend on which model classes the stripe-java version
 * happens to have. Every call goes to Unis's own Stripe account.
 *
 * Settings (Railway variables):
 *   stripe.secret.key       already set (the same key the rest of Unis uses)
 *   STRIPE_WEBHOOK_SECRET   the endpoint's signing secret (whsec_…) from
 *                           Stripe → Workbench → Webhooks
 */
@Component
public class StripeApi {

    private static final String BASE = "https://api.stripe.com/v1/";
    /** How old a webhook delivery may be (Stripe's own libraries use 5 minutes). */
    private static final long WEBHOOK_TOLERANCE_SECONDS = 300;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    @Value("${stripe.secret.key}")
    private String secretKey;

    @Value("${STRIPE_WEBHOOK_SECRET:}")
    private String webhookSecret;

    /** Thrown when Stripe answers with an error. The message is Stripe's own, safe to log. */
    public static class StripeException extends RuntimeException {
        private final int status;
        public StripeException(int status, String message) {
            super(message);
            this.status = status;
        }
        public int status() { return status; }
    }

    /** POST a form to Stripe. Keys use Stripe's bracket style, e.g. "line_items[0][price]". */
    public JsonNode post(String path, Map<String, String> form) {
        return post(path, form, null);
    }

    /** POST with an idempotency key, so a retried request can't create a second object. */
    public JsonNode post(String path, Map<String, String> form, String idempotencyKey) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)));
        if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey);
        return send(b.build());
    }

    /** GET an object from Stripe, e.g. "subscriptions/sub_123". */
    public JsonNode get(String path) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secretKey)
                .GET()
                .build();
        return send(req);
    }

    /** DELETE, e.g. canceling a subscription right away. */
    public JsonNode delete(String path) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secretKey)
                .DELETE()
                .build();
        return send(req);
    }

    private JsonNode send(HttpRequest req) {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode body = json.readTree(res.body());
            if (res.statusCode() >= 300) {
                String msg = body.path("error").path("message").asText("Stripe returned " + res.statusCode());
                throw new StripeException(res.statusCode(), msg);
            }
            return body;
        } catch (StripeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StripeException(0, "Interrupted while calling Stripe.");
        } catch (Exception e) {
            throw new StripeException(0, "Couldn't reach Stripe: " + e.getMessage());
        }
    }

    private static String encode(Map<String, String> form) {
        StringJoiner j = new StringJoiner("&");
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (e.getValue() == null) continue;
            j.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return j.toString();
    }

    /** A fresh ordered map for building forms. */
    public static Map<String, String> form() {
        return new LinkedHashMap<>();
    }

    public boolean webhookConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    /**
     * Checks a webhook's Stripe-Signature header: HMAC-SHA256 of
     * "timestamp.payload" with the endpoint's signing secret, compared in
     * constant time against every v1 signature, and no older than 5 minutes.
     */
    public boolean verifyWebhook(String payload, String signatureHeader) {
        if (!webhookConfigured() || payload == null || signatureHeader == null) return false;
        String timestamp = null;
        java.util.List<String> signatures = new java.util.ArrayList<>();
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if ("t".equals(kv[0])) timestamp = kv[1];
            else if ("v1".equals(kv[0])) signatures.add(kv[1]);
        }
        if (timestamp == null || signatures.isEmpty()) return false;
        long t;
        try {
            t = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - t) > WEBHOOK_TOLERANCE_SECONDS) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            String expectedHex = toHex(expected);
            for (String sig : signatures) {
                if (MessageDigest.isEqual(expectedHex.getBytes(StandardCharsets.UTF_8),
                        sig.getBytes(StandardCharsets.UTF_8))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public JsonNode parse(String payload) {
        try {
            return json.readTree(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not JSON.");
        }
    }
}
