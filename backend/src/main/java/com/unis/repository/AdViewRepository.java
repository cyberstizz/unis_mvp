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
    // REFERRAL EARNINGS — User earns 10% of ad revenue from users they referred
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Total revenue from ad views by users this person referred (lifetime).
     * The 10% share is applied in the service layer, not here.
     */
    @Query(value = "SELECT COALESCE(SUM(av.revenue_share), 0) FROM ad_views av " +
            "INNER JOIN referrals r ON av.user_id = r.referred_id " +
            "WHERE r.referrer_id = :userId",
            nativeQuery = true)
    BigDecimal sumReferralEarnings(@Param("userId") UUID userId);

    /**
     * Same as above but filtered to a date range (for monthly totals).
     */
    @Query(value = "SELECT COALESCE(SUM(av.revenue_share), 0) FROM ad_views av " +
            "INNER JOIN referrals r ON av.user_id = r.referred_id " +
            "WHERE r.referrer_id = :userId AND av.viewed_at >= :since",
            nativeQuery = true)
    BigDecimal sumReferralEarningsSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    /**
     * Count of ad views from referred users since a date (for dashboard stats).
     */
    @Query(value = "SELECT COUNT(*) FROM ad_views av " +
            "INNER JOIN referrals r ON av.user_id = r.referred_id " +
            "WHERE r.referrer_id = :userId AND av.viewed_at >= :since",
            nativeQuery = true)
    long countReferralViewsSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    /**
     * Daily referral earnings for charting.
     * Returns (date, total_revenue) grouped by day.
     */
    @Query(value = "SELECT DATE(av.viewed_at) as day, COALESCE(SUM(av.revenue_share), 0) as total " +
            "FROM ad_views av " +
            "INNER JOIN referrals r ON av.user_id = r.referred_id " +
            "WHERE r.referrer_id = :userId AND av.viewed_at >= :since " +
            "GROUP BY DATE(av.viewed_at) ORDER BY day",
            nativeQuery = true)
    List<Object[]> getDailyReferralEarnings(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    /**
     * Per-referral contribution breakdown.
     * Returns (referred_user_id, username, photo_url, view_count) for each referral.
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
    // SUPPORTER EARNINGS — Artist earns 15% from users who support them
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Total revenue from ad views where this artist is the supported artist (lifetime).
     * The 15% share is applied in the service layer.
     */
    @Query(value = "SELECT COALESCE(SUM(av.revenue_share), 0) FROM ad_views av " +
            "WHERE av.supported_artist_id = :artistId",
            nativeQuery = true)
    BigDecimal sumSupporterEarnings(@Param("artistId") UUID artistId);

    /**
     * Same but filtered by date range.
     */
    @Query(value = "SELECT COALESCE(SUM(av.revenue_share), 0) FROM ad_views av " +
            "WHERE av.supported_artist_id = :artistId AND av.viewed_at >= :since",
            nativeQuery = true)
    BigDecimal sumSupporterEarningsSince(@Param("artistId") UUID artistId, @Param("since") LocalDateTime since);

    /**
     * Daily supporter earnings for charting.
     */
    @Query(value = "SELECT DATE(av.viewed_at) as day, COALESCE(SUM(av.revenue_share), 0) as total " +
            "FROM ad_views av " +
            "WHERE av.supported_artist_id = :userId AND av.viewed_at >= :since " +
            "GROUP BY DATE(av.viewed_at) ORDER BY day",
            nativeQuery = true)
    List<Object[]> getDailySupporterEarnings(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    // ═══════════════════════════════════════════════════════════════════════════
    // REFERRER LOOKUP — Find who referred a given user
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find the direct referrer (Level 1) for a user.
     * Used during ad view tracking to attribute referral revenue.
     */
    @Query(value = "SELECT r.referrer_id FROM referrals r WHERE r.referred_id = :userId LIMIT 1",
            nativeQuery = true)
    UUID findReferrerIdForUser(@Param("userId") UUID userId);

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