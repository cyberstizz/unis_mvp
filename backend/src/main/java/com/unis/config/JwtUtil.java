package com.unis.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import io.jsonwebtoken.security.Keys;
import com.unis.service.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Component
public class JwtUtil {

    @Value("${spring.jwt.secret}")
    private String secret;

    @Value("${spring.jwt.expiration}")
    private Long expiration;

    // Stamps each new token with the account's current session version, so
    // ending all sessions (password change etc.) can refuse older tokens.
    @Autowired
    private SessionService sessionService;

    // Generate secret key from string
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // Extract username from token
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    // Extract expiration date from token
    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    // Extract specific claim
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * All claims of a valid sign-in token. Throws when the token is forged,
     * truncated, signed with another key, or expired.
     */
    public Claims parseClaims(String token) {
        return extractAllClaims(token);
    }

    // Extract all claims
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // Check if token is expired
    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    // Generate token with user details
    public String generateToken(String email, String userId, String role) {
        return generateToken(email, userId, role, false);
    }

    /**
     * mfaVerified marks a session that passed two-factor sign-in. Staff
     * permissions only apply to tokens carrying it (JwtRequestFilter); a staff
     * account's token without it works like a regular user's.
     */
    public String generateToken(String email, String userId, String role, boolean mfaVerified) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("role", role);
        if (mfaVerified) claims.put("mfa", true);
        // Session version: see SessionService. Older tokens are refused once
        // the account's sessions are ended.
        claims.put("sv", sessionService.versionForNewToken(UUID.fromString(userId)));
        return createToken(claims, email);
    }

    /** True when the token passed two-factor sign-in. */
    public boolean isMfaVerified(String token) {
        return Boolean.TRUE.equals(extractClaim(token, c -> c.get("mfa", Boolean.class)));
    }

    // ── Two-factor "code needed" pass ────────────────────────────────────────
    // Issued after a staff account's password is right, exchanged for a real
    // session once the 6-digit code is right. Signed with a DIFFERENT key
    // (derived from the main one), so it fails signature checks everywhere a
    // real session is accepted — it can't be used for anything else.

    private static final long MFA_PENDING_MS = 10 * 60 * 1000L;

    private SecretKey getMfaPendingKey() {
        try {
            byte[] derived = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("unis-mfa-pending:" + secret).getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(derived);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String generateMfaPendingToken(String email, String userId) {
        return Jwts.builder()
                .claim("userId", userId)
                .claim("purpose", "mfa")
                .subject(email)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + MFA_PENDING_MS))
                .signWith(getMfaPendingKey())
                .compact();
    }

    /** The account a "code needed" pass belongs to: {email, userId}. Throws when invalid or expired. */
    public String[] parseMfaPendingToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getMfaPendingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!"mfa".equals(claims.get("purpose", String.class))) {
            throw new io.jsonwebtoken.JwtException("Not a two-factor pass");
        }
        return new String[] { claims.getSubject(), claims.get("userId", String.class) };
    }

    // Create token with claims and subject
    private String createToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                // Random ID ("jti"): lets one device's token be signed out
                // without touching the person's other devices.
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    // Validate token
    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }

    // Simple validation 
    public Boolean validateToken(String token) {
        try {
            return !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }
}