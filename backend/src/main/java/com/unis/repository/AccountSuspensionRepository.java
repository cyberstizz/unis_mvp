package com.unis.repository;

import com.unis.entity.AccountSuspension;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountSuspensionRepository extends JpaRepository<AccountSuspension, UUID> {

    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END " +
           "FROM AccountSuspension s WHERE s.user.userId = :userId " +
           "AND s.liftedAt IS NULL " +
           "AND (s.expiresAt IS NULL OR s.expiresAt > CURRENT_TIMESTAMP)")
    boolean isUserSuspended(@Param("userId") UUID userId);

    @Query("SELECT s FROM AccountSuspension s WHERE s.user.userId = :userId " +
           "AND s.liftedAt IS NULL " +
           "AND (s.expiresAt IS NULL OR s.expiresAt > CURRENT_TIMESTAMP)")
    Optional<AccountSuspension> findActiveSuspension(@Param("userId") UUID userId);

    @Query("SELECT s FROM AccountSuspension s WHERE s.user.userId = :userId " +
           "ORDER BY s.createdAt DESC")
    List<AccountSuspension> findAllByUserId(@Param("userId") UUID userId);
}