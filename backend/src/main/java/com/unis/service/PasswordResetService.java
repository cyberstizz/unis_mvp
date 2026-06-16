package com.unis.service;

import com.unis.entity.PasswordResetToken;
import com.unis.entity.User;
import com.unis.repository.PasswordResetTokenRepository;
import com.unis.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class PasswordResetService {

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EmailService emailService;

    @Value("${app.frontend.url:https://unismusic.com}")
    private String frontendBaseUrl;

    /**
     * Request a password reset.
     * Always succeeds silently — never reveals whether the email exists.
     */
    public void requestPasswordReset(String email) {
        String normalizedEmail = email.trim().toLowerCase();
        Optional<User> userOpt = userRepository.findActiveByEmail(normalizedEmail);

        if (userOpt.isEmpty()) {
            // Silent return — don't reveal that the email doesn't exist
            return;
        }

        User user = userOpt.get();

        // Generate secure random token
        String token = UUID.randomUUID().toString();

        // Create token record (expires in 1 hour)
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .user(user)
                .token(token)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();

        passwordResetTokenRepository.save(resetToken);

        // Build reset URL
        String resetUrl = frontendBaseUrl + "/reset-password?token=" + token;

        // Send email (falls back to console if Resend key not configured)
        emailService.sendResetEmail(user.getEmail(), user.getUsername(), resetUrl);
    }

    /**
     * Reset password using a token.
     */
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired reset link"));

        // Validate token
        if (resetToken.getUsedAt() != null) {
            throw new RuntimeException("This reset link has already been used");
        }

        if (resetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("This reset link has expired");
        }

        // Validate new password
        if (newPassword == null || newPassword.length() < 8) {
            throw new RuntimeException("Password must be at least 8 characters");
        }

        // Update password
        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Mark token as used
        resetToken.setUsedAt(LocalDateTime.now());
        passwordResetTokenRepository.save(resetToken);
    }

    /**
     * Cleanup expired tokens (called from scheduled task)
     */
    public void cleanupExpiredTokens() {
        passwordResetTokenRepository.deleteExpiredTokens(LocalDateTime.now().minusHours(24));
    }
}