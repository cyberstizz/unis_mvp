package com.unis.repository;

import com.unis.entity.AdView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AdViewRepository extends JpaRepository<AdView, UUID> {

    // ═══════════════════════════════════════════════════════════════════════════
    // 3-LEVEL REFERRER LOOKUP
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find the direct referrer (Level 1) for a user.
     */
    @Query(value = "SELECT r.referrer_id FROM referrals r WHERE r.referred_id = :userId LIMIT 1",
            nativeQuery = true)
    UUID findReferrerIdForUser(@Param("userId") UUID userId);

    /**
     * Walk 3 levels up the referral chain for a user.
     * Returns [level1_id, level2_id, level3_id] — any can be null.
     */
    @Query(value = """
            SELECT 
                r1.referrer_id as level1,
                r2.referrer_id as level2,
                r3.referrer_id as level3
            FROM referrals r1
            LEFT JOIN referrals r2 ON r2.referred_id = r1.referrer_id
            LEFT JOIN referrals r3 ON r3.referred_id = r2.referrer_id
            WHERE r1.referred_id = :userId
            LIMIT 1
            """,
            nativeQuery = true)
    List<Object[]> findReferralChain(@Param("userId") UUID userId);

    // ═══════════════════════════════════════════════════════════════════════════
    // LEVEL 1 REFERRAL EARNINGS (10% share)
    // User earns from ad views by users they directly referred
    // ═══════════════════════════════════════════════════════════════════════════

    @Query(value = "SELECT COALESCE(SUM(av.revenue_share), 0) FROM ad_views av " +
            "WHERE av.referred_artist_id = :userId",
            nativeQuery = true)
    BigDecimal sumLevel1ReferralEarnings(@Param("userId") UUID userId);

    @Query(value = "SELECT COALESCE(SUM(av.revenue_share), 0) FROM ad_views av " +
            "WHERE av.referred_artist_id = :userId AND av.viewed_at >= :since",
            nativeQuery = true)
    BigDecimal sumLevel1ReferralEarningsSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    // ═══════════════════════════════════════════════════════════════════════════
    // LEVEL 2 REFERRAL EARNINGS (5% share)
    // User earns from ad views where they are the Level 2 referrer
    // ═══════════════════════════════════════════════════════════════════════════

    @Query(value = "SELECT COALESCE(SUM(av.revenue_share), 0) FROM ad_views av " +
            "WHERE av.referrer_level2_id = :userId",
            nativeQuery = true)
    BigDecimal sumLevel2ReferralEarnings(@Param("userId") UUID userId);

    @Query(value = "SELECT COALESCE(SUM(av.revenue_share), 0) FROM ad_views av " +
            "WHERE av.referrer_level2_id = :userId AND av.viewed_at >= :since",
            nativeQuery = true)
    BigDecimal sumLevel2ReferralEarningsSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    // ═══════════════════════════════════════════════════════════════════════════
    // LEVEL 3 REFERRAL EARNINGS (2% share)
    // User earns from ad views where they are the Level 3 referrer
    // ═══════════════════════════════════════════════════════════════════════════

    @Query(value = "SELECT COALESCE(SUM(av.revenue_share), 0) FROM ad_views av " +
            "WHERE av.referrer_level3_id = :userId",
            nativeQuery = true)
    BigDecimal sumLevel3ReferralEarnings(@Param("userId") UUID userId);

    @Query(value = "SELECT COALESCE(SUM(av.revenue_share), 0) FROM ad_views av " +
            "WHERE av.referrer_level3_id = :userId AND av.viewed_at >= :since",
            nativeQuery = true)
    BigDecimal sumLevel3ReferralEarningsSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    // ═══════════════════════════════════════════════════════════════════════════
    // SUPPORTER EARNINGS (15% share) — Artist earns from users who support them
    // ═══════════════════════════════════════════════════════════════════════════

    @Query(value = "SELECT COALESCE(SUM(av.revenue_share), 0) FROM ad_views av " +
            "WHERE av.supported_artist_id = :artistId",
            nativeQuery = true)
    BigDecimal sumSupporterEarnings(@Param("artistId") UUID artistId);

    @Query(value = "SELECT COALESCE(SUM(av.revenue_share), 0) FROM ad_views av " +
            "WHERE av.supported_artist_id = :artistId AND av.viewed_at >= :since",
            nativeQuery = true)
    BigDecimal sumSupporterEarningsSince(@Param("artistId") UUID artistId, @Param("since") LocalDateTime since);

    // ═══════════════════════════════════════════════════════════════════════════
    // COUNTS AND AGGREGATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Count ad views from users this person directly referred, since a date.
     */
    @Query(value = "SELECT COUNT(*) FROM ad_views av " +
            "WHERE av.referred_artist_id = :userId AND av.viewed_at >= :since",
            nativeQuery = true)
    long countLevel1ReferralViewsSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    /**
     * Daily referral earnings (all 3 levels combined) for charting.
     */
    @Query(value = """
            SELECT DATE(av.viewed_at) as day, 
                   COALESCE(SUM(CASE WHEN av.referred_artist_id = :userId THEN av.revenue_share ELSE 0 END), 0) as level1,
                   COALESCE(SUM(CASE WHEN av.referrer_level2_id = :userId THEN av.revenue_share ELSE 0 END), 0) as level2,
                   COALESCE(SUM(CASE WHEN av.referrer_level3_id = :userId THEN av.revenue_share ELSE 0 END), 0) as level3
            FROM ad_views av
            WHERE (av.referred_artist_id = :userId OR av.referrer_level2_id = :userId OR av.referrer_level3_id = :userId)
              AND av.viewed_at >= :since
            GROUP BY DATE(av.viewed_at) ORDER BY day
            """,
            nativeQuery = true)
    List<Object[]> getDailyReferralEarningsAllLevels(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    /**
     * Daily supporter earnings for charting.
     */
    @Query(value = "SELECT DATE(av.viewed_at) as day, COALESCE(SUM(av.revenue_share), 0) as total " +
            "FROM ad_views av " +
            "WHERE av.supported_artist_id = :userId AND av.viewed_at >= :since " +
            "GROUP BY DATE(av.viewed_at) ORDER BY day",
            nativeQuery = true)
    List<Object[]> getDailySupporterEarnings(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    /**
     * Per-referral contribution breakdown (Level 1 direct referrals only).
     */
    @Query(value = "SELECT r.referred_id, u.username, u.photo_url, COUNT(av.ad_view_id) as views " +
            "FROM referrals r " +
            "INNER JOIN users u ON r.referred_id = u.user_id " +
            "LEFT JOIN ad_views av ON av.user_id = r.referred_id " +
            "WHERE r.referrer_id = :userId " +
            "GROUP BY r.referred_id, u.username, u.photo_url " +
            "ORDER BY views DESC",
            nativeQuery = true)
    List<Object[]> getReferralContributions(@Param("userId") UUID userId);

    // ═══════════════════════════════════════════════════════════════════════════
    // LEGACY QUERIES (kept for backward compatibility)
    // ═══════════════════════════════════════════════════════════════════════════

    @Query("SELECT COALESCE(SUM(a.revenueShare), 0) FROM AdView a WHERE a.supportedArtist.userId = :artistId AND CAST(a.viewedAt AS LocalDate) = :date")
    BigDecimal sumEarningsByDay(@Param("artistId") UUID artistId, @Param("date") LocalDate date);

    @Query("SELECT COALESCE(SUM(a.revenueShare), 0) FROM AdView a WHERE a.referredArtist.userId = :artistId AND CAST(a.viewedAt AS LocalDate) = :date")
    BigDecimal sumEarningsByDayFromReferrals(@Param("artistId") UUID artistId, @Param("date") LocalDate date);

    @Query("SELECT CAST(a.viewedAt AS LocalDate) as day, SUM(a.revenueShare) as total FROM AdView a WHERE a.supportedArtist.userId = :artistId AND a.viewedAt >= :startDate GROUP BY CAST(a.viewedAt AS LocalDate) ORDER BY day")
    List<Object[]> getEarningsLastDays(@Param("artistId") UUID artistId, @Param("startDate") LocalDateTime startDate);

    // ═══════════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════════════

    @Modifying
    @Query("DELETE FROM AdView av WHERE av.user.userId = :userId")
    void deleteByUserUserId(@Param("userId") UUID userId);
}