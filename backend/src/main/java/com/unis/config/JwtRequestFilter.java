package com.unis.config;

import com.unis.service.SessionService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Date;
import java.util.Optional;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtRequestFilter.class);

    @Autowired
    private AuthUserCache authUserCache;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private SessionService sessionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        final String authorizationHeader = request.getHeader("Authorization");

        // The token is checked and read once per request. (It used to be parsed
        // and its signature verified four separate times.)
        Claims claims = null;
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            String jwt = authorizationHeader.substring(7);
            try {
                claims = jwtUtil.parseClaims(jwt);
            } catch (ExpiredJwtException e) {
                // Normal: the app refreshes the session. Not worth a log line.
            } catch (JwtException | IllegalArgumentException e) {
                // Forged, truncated or wrong-key token: treat the request as signed out.
                log.debug("[Auth] rejected token: {}", e.getClass().getSimpleName());
            }
        }

        String username = claims != null ? claims.getSubject() : null;

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

            // Deleted or suspended since the token was issued: signed out.
            // Cached for a minute (AuthUserCache) instead of three queries per request.
            Optional<UserDetails> account = authUserCache.find(username);
            if (account.isEmpty()) {
                chain.doFilter(request, response);
                return;
            }
            UserDetails userDetails = account.get();

            Date expiration = claims.getExpiration();
            boolean valid = username.equals(userDetails.getUsername())
                    && (expiration == null || expiration.after(new Date()));

            if (valid) {
                // C6 FIX: Extract userId from JWT claims and store as credentials
                // so downstream controllers can access it via SecurityUtils.getAuthenticatedUserId()
                String userId = claims.get("userId", String.class);

                // Signed out on this device, or the account's sessions were
                // ended (password changed, etc.): treat as signed out. The
                // request carries on as a guest and protected routes answer
                // 401, which makes the apps clear the stored token.
                if (sessionService.isEnded(userId, claims.get("sv", Integer.class), claims.getId())) {
                    chain.doFilter(request, response);
                    return;
                }

                // Staff permissions only for sessions that passed two-factor
                // sign-in. A staff account's token without it (issued before
                // 2FA, or before the role was granted) acts as a regular user.
                boolean mfaVerified = Boolean.TRUE.equals(claims.get("mfa", Boolean.class));
                var authorities = mfaVerified
                        ? userDetails.getAuthorities()
                        : java.util.List.<org.springframework.security.core.GrantedAuthority>of();
                UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails, userId, authorities);
                usernamePasswordAuthenticationToken
                        .setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(usernamePasswordAuthenticationToken);
            }
        }
        chain.doFilter(request, response);
    }
}
