package com.unis.repository;

import com.unis.entity.SupportPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SupportPaymentRepository extends JpaRepository<SupportPayment, Long> {

    // Incoming support for an artist (dashboard "received")
    List<SupportPayment> findByArtistIdOrderByCreatedAtDesc(UUID artistId);

    // Outgoing support from a listener (their "sent" history)
    List<SupportPayment> findBySupporterIdOrderByCreatedAtDesc(UUID supporterId);

    // Idempotency + lookup by Stripe PaymentIntent
    Optional<SupportPayment> findByStripePaymentIntentId(String stripePaymentIntentId);

    boolean existsByStripePaymentIntentId(String stripePaymentIntentId);

    // ── Aggregates for the artist profile + dashboard ──

    @Query("SELECT COALESCE(SUM(s.amount), 0) FROM SupportPayment s " +
           "WHERE s.artistId = :artistId AND s.status = 'completed'")
    long sumReceivedCents(@Param("artistId") UUID artistId);

    @Query("SELECT COALESCE(SUM(s.amount), 0) FROM SupportPayment s " +
           "WHERE s.artistId = :artistId AND s.status = 'completed' AND s.createdAt >= :since")
    long sumReceivedCentsSince(@Param("artistId") UUID artistId, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(DISTINCT s.supporterId) FROM SupportPayment s " +
           "WHERE s.artistId = :artistId AND s.status = 'completed'")
    long countDistinctSupporters(@Param("artistId") UUID artistId);
}