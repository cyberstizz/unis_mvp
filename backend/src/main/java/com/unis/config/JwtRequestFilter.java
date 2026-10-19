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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtRequestFilter.class);

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private SessionService sessionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        final String authorizationHeader = request.getHeader("Authorization");

        String username = null;
        String jwt = null;

        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            jwt = authorizationHeader.substring(7);
            try {
                username = jwtUtil.extractUsername(jwt);
            } catch (ExpiredJwtException e) {
                // Normal: the app refreshes the session. Not worth a log line.
            } catch (JwtException | IllegalArgumentException e) {
                // Forged, truncated or wrong-key token: treat the request as signed out.
                log.debug("[Auth] rejected token: {}", e.getClass().getSimpleName());
            }
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

            UserDetails userDetails;
            try {
                userDetails = this.userDetailsService.loadUserByUsername(username);
            } catch (UsernameNotFoundException e) {
                // Deleted or suspended since the token was issued: signed out.
                // (Before, this escaped as a 500 whose message included the email.)
                chain.doFilter(request, response);
                return;
            }

            if (jwtUtil.validateToken(jwt, userDetails)) {

                // C6 FIX: Extract userId from JWT claims and store as credentials
                // so downstream controllers can access it via SecurityUtils.getAuthenticatedUserId()
                Claims claims = jwtUtil.parseClaims(jwt);
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
                var authorities = jwtUtil.isMfaVerified(jwt)
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