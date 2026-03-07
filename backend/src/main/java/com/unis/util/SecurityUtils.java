package com.unis.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.UUID;

/**
 * Extracts authenticated user information from the SecurityContext.
 * Requires JwtRequestFilter to store userId as the credentials field
 * of UsernamePasswordAuthenticationToken (see C6 fix in JwtRequestFilter).
 */
public class SecurityUtils {

    public static UUID getAuthenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getCredentials() == null) {
            throw new RuntimeException("No authenticated user in SecurityContext");
        }
        return UUID.fromString((String) auth.getCredentials());
    }
}