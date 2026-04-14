package com.unis.repository;

import com.unis.entity.Purchase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    // Check if a user already bought a specific song
    boolean existsByBuyerIdAndSongId(UUID buyerId, UUID songId);

    // Get all purchases by a specific buyer
    List<Purchase> findByBuyerIdOrderByCreatedAtDesc(UUID buyerId);

    // Get all sales for a specific artist
    List<Purchase> findByArtistIdOrderByCreatedAtDesc(UUID artistId);

    // Get all purchases for a specific song
    List<Purchase> findBySongIdOrderByCreatedAtDesc(UUID songId);

    // Lookup by Stripe PaymentIntent ID (for webhook handling)
    Optional<Purchase> findByStripePaymentIntentId(String stripePaymentIntentId);
}