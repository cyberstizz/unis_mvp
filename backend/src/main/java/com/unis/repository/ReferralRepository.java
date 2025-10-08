package com.unis.repository;

import com.unis.entity.Referral;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface ReferralRepository extends JpaRepository<Referral, UUID> {
    @Query("SELECT COUNT(r) FROM Referral r WHERE r.referrer.userId = :userId")
    Long countByReferrer(@Param("userId") UUID userId);

    boolean existsByReferrerAndReferred(User referrer, User referred);
}