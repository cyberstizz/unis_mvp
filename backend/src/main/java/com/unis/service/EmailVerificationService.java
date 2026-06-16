// src/main/java/com/unis/service/EmailVerificationService.java
package com.unis.service;

import com.unis.entity.EmailVerificationToken;
import com.unis.entity.User;
import com.unis.repository.EmailVerificationTokenRepository;
import com.unis.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class EmailVerificationService {

    @Autowired private EmailVerificationTokenRepository tokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EmailService emailService;

    @Value("${app.frontend.url:https://unismusic.com}")
    private String frontendBaseUrl;

    /** Issue a fresh 24h verification token and email the link. */
    public void sendVerification(User user) {
        String token = UUID.randomUUID().toString();

        EmailVerificationToken record = EmailVerificationToken.builder()
                .user(user)
                .token(token)
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();
        tokenRepository.save(record);

        String verifyUrl = frontendBaseUrl + "/verify-email?token=" + token;
        emailService.sendVerificationEmail(user.getEmail(), user.getUsername(), verifyUrl);
    }

    /** Verify a token and flip the user's email_verified flag.
     *  A second click on the same link is treated as success (no error). */
    public void verify(String token) {
        EmailVerificationToken record = tokenRepository.findByToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired verification link"));

        if (record.getUsedAt() != null) {
            return; // already used — idempotent so double-clicks don't 400
        }
        if (record.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("This verification link has expired. Request a new one.");
        }

        User user = record.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);

        record.setUsedAt(LocalDateTime.now());
        tokenRepository.save(record);
    }

    /** Re-send verification. Silent if no matching unverified account exists. */
    public void resend(String email) {
        String normalized = email.trim().toLowerCase();
        Optional<User> userOpt = userRepository.findActiveByEmail(normalized);
        if (userOpt.isEmpty()) return;

        User user = userOpt.get();
        if (Boolean.TRUE.equals(user.getEmailVerified())) return; // nothing to do
        sendVerification(user);
    }

    /** Cleanup expired tokens (wire into your existing scheduled cleanup). */
    public void cleanupExpiredTokens() {
        tokenRepository.deleteExpiredTokens(LocalDateTime.now().minusHours(48));
    }
}