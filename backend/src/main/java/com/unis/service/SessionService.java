package com.unis.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Decides whether a sign-in token is still allowed, so that signing out and
 * changing a password actually end sessions instead of waiting out the
 * token's 24 hours.
 *
 * Two ways a token stops working:
 *  - Signed out on one device: its random ID ("jti") goes into
 *    revoked_sessions until the moment it would have expired anyway.
 *  - Every session of an account ended (password changed or reset, account
 *    deleted, staff two-factor reset): users.session_version goes up by one.
 *    Each token carries the version it was issued under ("sv"), so every
 *    older token is refused at once.
 *
 * Each request is checked against small in-memory caches, so this adds no
 * database work on most requests. The caches are cleared the moment
 * something changes. Unis runs one backend instance, so that's immediate.
 * With several instances, another instance could accept an ended session
 * for up to {@link #VERSION_CACHE_MINUTES} minutes.
 *
 * Tables: backend/sql/V2026_10g__sessions.sql
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    static final int VERSION_CACHE_MINUTES = 2;

    private final JdbcTemplate jdbc;

    /** userId → current session_version. */
    private final Cache<UUID, Integer> versions = Caffeine.newBuilder()
            .expireAfterWrite(VERSION_CACHE_MINUTES, TimeUnit.MINUTES)
            .maximumSize(200_000)
            .build();

    /** token ID → signed out? Kept a while so each token costs one lookup per 10 minutes. */
    private final Cache<String, Boolean> signedOut = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(500_000)
            .build();

    public SessionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Checking ─────────────────────────────────────────────────────────────

    /**
     * True when a token must be refused: signed out, or issued before the
     * account's sessions were last ended.
     *
     * @param userId       the token's userId claim
     * @param tokenVersion the token's "sv" claim (tokens from before this
     *                     change have none and count as version 0)
     * @param tokenId      the token's "jti" claim (null on older tokens)
     */
    public boolean isEnded(String userId, Integer tokenVersion, String tokenId) {
        UUID id;
        try {
            id = UUID.fromString(userId);
        } catch (RuntimeException e) {
            return true; // no usable userId: not a session we issued
        }
        int issuedUnder = tokenVersion == null ? 0 : tokenVersion;
        if (issuedUnder != currentVersion(id)) {
            return true;
        }
        return tokenId != null && isSignedOut(tokenId);
    }

    /** The version a token issued right now must carry. Read fresh, never from the cache. */
    public int versionForNewToken(UUID userId) {
        int v = readVersion(userId);
        versions.put(userId, v);
        return v;
    }

    private int currentVersion(UUID userId) {
        Integer v = versions.get(userId, this::readVersion);
        return v == null ? 0 : v;
    }

    private int readVersion(UUID userId) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT session_version FROM users WHERE user_id = ?", Integer.class, userId);
        return rows.isEmpty() || rows.get(0) == null ? 0 : rows.get(0);
    }

    private boolean isSignedOut(String tokenId) {
        Boolean out = signedOut.get(tokenId, id -> !jdbc.queryForList(
                "SELECT 1 FROM revoked_sessions WHERE token_id = ?", Integer.class, id).isEmpty());
        return Boolean.TRUE.equals(out);
    }

    // ── Ending sessions ──────────────────────────────────────────────────────

    /** Signs out one token (one device). Kept until the token would have expired. */
    public void signOut(String tokenId, UUID userId, Instant expiresAt) {
        if (tokenId == null || tokenId.isBlank() || expiresAt == null) return;
        jdbc.update("INSERT INTO revoked_sessions (token_id, user_id, expires_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT (token_id) DO NOTHING",
                tokenId, userId, Timestamp.from(expiresAt));
        signedOut.put(tokenId, true);
    }

    /**
     * Ends every session of an account, on every device. Use after a password
     * change or reset, an account deletion, or a staff two-factor reset.
     */
    public void endAllSessions(UUID userId) {
        List<Integer> rows = jdbc.queryForList(
                "UPDATE users SET session_version = session_version + 1 WHERE user_id = ? "
                        + "RETURNING session_version", Integer.class, userId);
        versions.invalidate(userId);
        // Inside a database transaction the change isn't visible to other
        // requests until it commits; clear the cache again at that point so
        // nothing re-caches the old version in between.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    versions.invalidate(userId);
                }
            });
        }
        log.info("[Sessions] action=end_all userId={} version={}", userId, rows.isEmpty() ? "?" : rows.get(0));
    }

    // ── Housekeeping ─────────────────────────────────────────────────────────

    /** Signed-out tokens past their expiry are refused by the expiry check anyway. */
    @Scheduled(cron = "0 17 4 * * *")
    public void deleteExpiredSignOuts() {
        try {
            int n = jdbc.update("DELETE FROM revoked_sessions WHERE expires_at < now()");
            if (n > 0) log.info("[Sessions] action=cleanup deleted={}", n);
        } catch (RuntimeException e) {
            log.warn("[Sessions] action=cleanup status=fail err={}", e.getMessage());
        }
    }
}
