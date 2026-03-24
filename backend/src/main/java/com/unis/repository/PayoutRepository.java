package com.unis.repository;

import com.unis.entity.Payout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    /**
     * Get all payouts for a user, newest first.
     */
    List<Payout> findByUser_UserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Sum of all completed payouts for a user (to calculate remaining balance).
     */
    @Query(value = "SELECT COALESCE(SUM(p.amount), 0) FROM payouts p " +
            "WHERE p.user_id = :userId AND p.status = 'completed'",
            nativeQuery = true)
    BigDecimal sumCompletedPayouts(@Param("userId") UUID userId);

    /**
     * Check if there's a pending or processing payout for this user.
     */
    @Query(value = "SELECT COUNT(*) > 0 FROM payouts p " +
            "WHERE p.user_id = :userId AND p.status IN ('pending', 'processing')",
            nativeQuery = true)
    boolean hasActivePayout(@Param("userId") UUID userId);
}