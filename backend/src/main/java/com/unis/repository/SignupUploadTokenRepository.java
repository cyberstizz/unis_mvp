package com.unis.repository;

import com.unis.entity.SignupUploadToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface SignupUploadTokenRepository extends JpaRepository<SignupUploadToken, UUID> {

    Optional<SignupUploadToken> findByToken(String token);

    @Modifying
    @Transactional
    @Query("DELETE FROM SignupUploadToken t WHERE t.expiresAt < :cutoff")
    void deleteExpiredTokens(LocalDateTime cutoff);
}