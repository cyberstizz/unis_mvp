// src/main/java/com/unis/service/SignupUploadService.java
package com.unis.service;

import com.unis.entity.SignupUploadToken;
import com.unis.entity.User;
import com.unis.repository.SignupUploadTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Transactional
public class SignupUploadService {

    @Autowired private SignupUploadTokenRepository tokenRepository;

    /** Short-lived (30 min), single-use token authorizing exactly one debut-song
     *  upload for a brand-new account. It is NOT a login token and grants no app access. */
    public String issue(User user) {
        String token = UUID.randomUUID().toString();
        SignupUploadToken record = SignupUploadToken.builder()
                .user(user)
                .token(token)
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build();
        tokenRepository.save(record);
        return token;
    }

    /** Validate + consume a token, returning the userId it authorizes. */
    public UUID consume(String token) {
        SignupUploadToken record = tokenRepository.findByToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid signup upload token"));

        if (record.getUsedAt() != null) {
            throw new RuntimeException("This signup upload token has already been used");
        }
        if (record.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("This signup upload token has expired");
        }

        record.setUsedAt(LocalDateTime.now());
        tokenRepository.save(record);
        return record.getUser().getUserId();
    }

    public void cleanupExpiredTokens() {
        tokenRepository.deleteExpiredTokens(LocalDateTime.now().minusHours(24));
    }
}