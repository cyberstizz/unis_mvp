package com.unis.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The signed-in account behind each request, kept for one minute.
 *
 * JwtRequestFilter used to call UserDetailsService on every request: three
 * database round trips (the account by email, the suspension check, the staff
 * role) before any endpoint ran, on every signed-in call. This keeps the result
 * per email for {@link #TTL_SECONDS} seconds, so a busy session costs those
 * three queries about once a minute instead of on every click.
 *
 * Only the request filter reads from here. Sign-in itself (the password check)
 * still goes to the database every time, so a changed password is never
 * checked against a cached hash.
 *
 * What still takes effect at once:
 *  - signing out, password changes and account deletion: SessionService ends
 *    the token itself, checked on every request;
 *  - suspensions and staff role changes: AdminUserService calls evictAll().
 * A suspension that simply expires is noticed within a minute.
 *
 * One backend instance: with several Railway replicas, the evictAll() above
 * only reaches the replica that handled the admin's request; the others catch
 * up within the minute.
 */
@Component
public class AuthUserCache {

    static final int TTL_SECONDS = 60;

    /** Absent means "no active account with this email" (deleted or suspended). */
    private final Cache<String, Optional<UserDetails>> byEmail = Caffeine.newBuilder()
            .expireAfterWrite(TTL_SECONDS, TimeUnit.SECONDS)
            .maximumSize(100_000)
            .build();

    private final UserDetailsService userDetailsService;

    public AuthUserCache(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    /** The account for a token's subject, or empty when it can't sign in (deleted, suspended). */
    public Optional<UserDetails> find(String email) {
        if (email == null) return Optional.empty();
        return byEmail.get(email.toLowerCase(), key -> {
            try {
                return Optional.of(userDetailsService.loadUserByUsername(email));
            } catch (UsernameNotFoundException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Forget every cached account (after a suspension or a staff role change).
     * Inside a database transaction the change isn't visible to other requests
     * until it commits, so the cache is cleared again at that point; otherwise a
     * request in between could cache the old state for another minute.
     */
    public void evictAll() {
        byEmail.invalidateAll();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    byEmail.invalidateAll();
                }
            });
        }
    }
}
