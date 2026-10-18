package com.unis.service.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Time-based one-time codes (RFC 6238) — the 6-digit codes that Google
 * Authenticator, Microsoft Authenticator, 1Password, Salesforce Authenticator
 * and every other authenticator app produce. Standard settings that all of
 * them understand: HMAC-SHA1, 6 digits, a new code every 30 seconds.
 *
 * No library and no outside service: the standard is a few lines of crypto
 * that ships with Java.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final int PERIOD_SECONDS = 30;
    /** Codes from one window before or after are accepted, for phone clocks that drift a little. */
    public static final int DRIFT_STEPS = 1;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Totp() {}

    /** A new random secret: 20 bytes (160 bits), as RFC 4226 recommends. */
    public static byte[] newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /** The 30-second window a moment falls in. */
    public static long stepAt(long epochSeconds) {
        return Math.floorDiv(epochSeconds, PERIOD_SECONDS);
    }

    /** The code for one window, zero-padded to 6 digits. */
    public static String codeAt(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                       | ((hash[offset + 1] & 0xFF) << 16)
                       | ((hash[offset + 2] & 0xFF) << 8)
                       |  (hash[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    /**
     * The window a code belongs to, if it matches the current window or one
     * either side of it; -1 when it matches none. Compares in constant time.
     */
    public static long matchingStep(byte[] secret, String code, long nowEpochSeconds) {
        if (code == null) return -1;
        String digits = code.replaceAll("\\s", "");
        if (!digits.matches("\\d{" + DIGITS + "}")) return -1;
        long now = stepAt(nowEpochSeconds);
        long found = -1;
        for (long step = now - DRIFT_STEPS; step <= now + DRIFT_STEPS; step++) {
            byte[] expected = codeAt(secret, step).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, digits.getBytes(StandardCharsets.US_ASCII))) {
                found = step;
            }
        }
        return found;
    }

    /** RFC 4648 Base32 without padding — the form authenticator apps expect. */
    public static String base32(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        if (bits > 0) out.append(BASE32[(buffer << (5 - bits)) & 0x1F]);
        return out.toString();
    }

    /**
     * The link a QR code holds. Scanning it adds "Unis (you@example.com)" to
     * the authenticator app.
     */
    public static String otpauthUri(String issuer, String accountName, byte[] secret) {
        String label = enc(issuer) + ":" + enc(accountName);
        return "otpauth://totp/" + label
            + "?secret=" + base32(secret)
            + "&issuer=" + enc(issuer)
            + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
