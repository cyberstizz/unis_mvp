package com.unis.service.mfa;

import com.unis.config.RateLimiter;
import com.unis.repository.AdminRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Two-factor sign-in for staff accounts (moderator, admin, super admin).
 *
 * Every staff account must use it: the first sign-in after a role is granted
 * walks through setup (scan a QR code, confirm one code, save ten recovery
 * codes); every sign-in after that asks for the current 6-digit code.
 * Regular listeners and artists are not affected.
 *
 * Tables: admin_mfa and admin_mfa_recovery_codes (sql/V2026_10f__admin_mfa.sql).
 */
@Service
public class AdminMfaService {

    private static final Logger log = LoggerFactory.getLogger(AdminMfaService.class);

    public enum Status { NONE, PENDING, ENABLED }

    /** What setup shows: the key to type in by hand, and the link the QR code holds. */
    public record Setup(String manualKey, String otpauthUri) {}

    /** Thrown for a wrong code, too many attempts, or setup in the wrong state. Message is safe to show. */
    public static class MfaException extends RuntimeException {
        public MfaException(String message) { super(message); }
    }

    private static final String ISSUER = "Unis";
    private static final int RECOVERY_CODES = 10;
    /** Wrong codes allowed per account per 15 minutes before it must wait. */
    private static final int ATTEMPTS_PER_WINDOW = 6;
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);
    private static final char[] RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AdminRoleRepository adminRoleRepository;
    private final RateLimiter rateLimiter;
    private final String keyBase64;

    public AdminMfaService(JdbcTemplate jdbc,
                           AdminRoleRepository adminRoleRepository,
                           RateLimiter rateLimiter,
                           @Value("${unis.mfa.key:}") String keyBase64) {
        this.jdbc = jdbc;
        this.adminRoleRepository = adminRoleRepository;
        this.rateLimiter = rateLimiter;
        this.keyBase64 = keyBase64;
    }

    /** True for moderators, admins and super admins — everyone who must use 2FA. */
    public boolean isStaff(UUID userId) {
        return adminRoleRepository.findByUserId(userId).isPresent();
    }

    /** False when UNIS_MFA_KEY is missing or malformed; staff can't sign in until it's set. */
    public boolean isConfigured() {
        try {
            new MfaCrypto(keyBase64);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public Status status(UUID userId) {
        List<Boolean> rows = jdbc.queryForList(
            "SELECT enabled_at IS NOT NULL FROM admin_mfa WHERE user_id = ?", Boolean.class, userId);
        if (rows.isEmpty()) return Status.NONE;
        return Boolean.TRUE.equals(rows.get(0)) ? Status.ENABLED : Status.PENDING;
    }

    /**
     * Starts (or restarts) setup with a fresh secret. Refused once 2FA is on:
     * turning it off takes a super admin or the reset SQL, never the account itself
     * mid-sign-in.
     */
    @Transactional
    public Setup beginSetup(UUID userId, String accountEmail) {
        if (status(userId) == Status.ENABLED) {
            throw new MfaException("Two-factor sign-in is already set up for this account.");
        }
        byte[] secret = Totp.newSecret();
        String enc = crypto().encrypt(secret);
        jdbc.update("""
            INSERT INTO admin_mfa (user_id, secret_enc, enabled_at, last_used_step, created_at, updated_at)
            VALUES (?, ?, NULL, 0, NOW(), NOW())
            ON CONFLICT (user_id) DO UPDATE
               SET secret_enc = EXCLUDED.secret_enc, enabled_at = NULL, last_used_step = 0, updated_at = NOW()
            """, userId, enc);
        log.info("[MFA] setup started user={}", userId);
        return new Setup(groupForReading(Totp.base32(secret)), Totp.otpauthUri(ISSUER, accountEmail, secret));
    }

    /**
     * Confirms setup with the first code from the app, turns 2FA on, and
     * returns ten new recovery codes. They are shown once and only stored hashed.
     */
    @Transactional
    public List<String> confirmSetup(UUID userId, String code) {
        throttle(userId);
        if (status(userId) != Status.PENDING) {
            throw new MfaException("Start setup again: the setup session has expired.");
        }
        byte[] secret = secretOf(userId);
        long step = Totp.matchingStep(secret, code, nowSeconds());
        if (step < 0) {
            throw new MfaException("That code didn't match. Check the time on your phone is set automatically, then try the newest code.");
        }
        jdbc.update("UPDATE admin_mfa SET enabled_at = NOW(), last_used_step = ?, updated_at = NOW() WHERE user_id = ?",
            step, userId);
        List<String> codes = replaceRecoveryCodes(userId);
        log.info("[MFA] enabled user={}", userId);
        return codes;
    }

    /**
     * Checks a sign-in code: the 6-digit code from the app, or one of the
     * recovery codes (each works once). A 6-digit code can't be reused, even
     * within its 30 seconds.
     */
    @Transactional
    public boolean verify(UUID userId, String code) {
        throttle(userId);
        if (status(userId) != Status.ENABLED || code == null) return false;
        String trimmed = code.trim();

        if (trimmed.replaceAll("\\s", "").matches("\\d{" + Totp.DIGITS + "}")) {
            long step = Totp.matchingStep(secretOf(userId), trimmed, nowSeconds());
            if (step < 0) return false;
            // Only succeeds if this window is newer than the last one used: no replay.
            int updated = jdbc.update(
                "UPDATE admin_mfa SET last_used_step = ?, updated_at = NOW() WHERE user_id = ? AND last_used_step < ?",
                step, userId, step);
            return updated == 1;
        }

        String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.length() != 10) return false;
        int used = jdbc.update("""
            UPDATE admin_mfa_recovery_codes SET used_at = NOW()
            WHERE id = (SELECT id FROM admin_mfa_recovery_codes
                        WHERE user_id = ? AND code_hash = ? AND used_at IS NULL LIMIT 1)
            """, userId, sha256(normalized));
        if (used == 1) log.warn("[MFA] recovery code used user={} remaining={}", userId, recoveryCodesLeft(userId));
        return used == 1;
    }

    public int recoveryCodesLeft(UUID userId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM admin_mfa_recovery_codes WHERE user_id = ? AND used_at IS NULL",
            Integer.class, userId);
        return n == null ? 0 : n;
    }

    /** Removes 2FA from an account (super admin action, e.g. a lost phone). Setup runs again at next sign-in. */
    @Transactional
    public void reset(UUID userId) {
        jdbc.update("DELETE FROM admin_mfa_recovery_codes WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM admin_mfa WHERE user_id = ?", userId);
        log.warn("[MFA] reset user={}", userId);
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void throttle(UUID userId) {
        if (!rateLimiter.hit("mfa|" + userId, ATTEMPTS_PER_WINDOW, ATTEMPT_WINDOW).allowed()) {
            log.warn("[MFA] too many attempts user={}", userId);
            throw new MfaException("Too many attempts. Wait 15 minutes, then try again.");
        }
    }

    private byte[] secretOf(UUID userId) {
        String enc = jdbc.queryForObject("SELECT secret_enc FROM admin_mfa WHERE user_id = ?", String.class, userId);
        return crypto().decrypt(enc);
    }

    private List<String> replaceRecoveryCodes(UUID userId) {
        jdbc.update("DELETE FROM admin_mfa_recovery_codes WHERE user_id = ?", userId);
        List<String> shown = new ArrayList<>(RECOVERY_CODES);
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder raw = new StringBuilder(10);
            for (int j = 0; j < 10; j++) raw.append(RECOVERY_ALPHABET[RANDOM.nextInt(RECOVERY_ALPHABET.length)]);
            jdbc.update("INSERT INTO admin_mfa_recovery_codes (user_id, code_hash) VALUES (?, ?)",
                userId, sha256(raw.toString()));
            shown.add(raw.substring(0, 5) + "-" + raw.substring(5));
        }
        return shown;
    }

    private MfaCrypto crypto() {
        try {
            return new MfaCrypto(keyBase64);
        } catch (IllegalStateException e) {
            throw new MfaException("Admin sign-in is unavailable: " + e.getMessage() + " on the server.");
        }
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    /** "ABCD EFGH IJKL ..." — easier to type into an app by hand. */
    private static String groupForReading(String base32) {
        return base32.replaceAll("(.{4})(?!$)", "$1 ");
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
