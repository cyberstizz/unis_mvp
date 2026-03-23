package com.unis.service;

import com.unis.entity.AdView;
import com.unis.entity.User;
import com.unis.entity.Supporter;
import com.unis.repository.AdViewRepository;
import com.unis.repository.ReferralRepository;
import com.unis.repository.SupporterRepository;
import com.unis.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional
public class EarningsService {

    @Autowired
    private AdViewRepository adViewRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private SupporterRepository supporterRepository;

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTANTS — AdSense Display Ad Revenue Split
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Estimated CPM (cost per mille / 1000 views).
     * $3.50 is a conservative industry average for music/entertainment.
     * This translates to $0.0035 per single ad view.
     * Update this when real AdSense data is available.
     */
    private static final BigDecimal CPM = new BigDecimal("3.50");
    private static final BigDecimal REVENUE_PER_VIEW = CPM.divide(new BigDecimal("1000"), 10, RoundingMode.HALF_UP);

    // AdSense split percentages (from Unis revenue model)
    private static final BigDecimal UNIS_SHARE = new BigDecimal("0.75");       // 75%
    private static final BigDecimal REFERRER_SHARE = new BigDecimal("0.10");   // 10%
    private static final BigDecimal SUPPORTER_SHARE = new BigDecimal("0.15");  // 15%

    // ═══════════════════════════════════════════════════════════════════════════
    // AD VIEW TRACKING — Called when a user sees a display ad
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Track a display ad view and attribute revenue.
     *
     * Revenue per view = CPM / 1000
     * Split: 75% Unis, 10% direct referrer, 15% supported artist
     *
     * If no referrer exists, that 10% goes to Unis.
     * If no supported artist exists, that 15% goes to Unis.
     */
    public AdView trackAdView(UUID viewerUserId) {
        User viewer = userRepository.findById(viewerUserId)
                .orElseThrow(() -> new RuntimeException("User not found: " + viewerUserId));

        // Look up the viewer's direct referrer (Level 1 only for AdSense)
        UUID referrerId = adViewRepository.findReferrerIdForUser(viewerUserId);

        // Look up the viewer's supported artist
        UUID supportedArtistId = viewer.getSupportedArtistId();

        // Calculate revenue attribution
        BigDecimal totalRevenue = REVENUE_PER_VIEW;
        BigDecimal referrerAmount = BigDecimal.ZERO;
        BigDecimal supportedArtistAmount = BigDecimal.ZERO;

        if (referrerId != null) {
            referrerAmount = totalRevenue.multiply(REFERRER_SHARE).setScale(10, RoundingMode.HALF_UP);
        }

        if (supportedArtistId != null) {
            supportedArtistAmount = totalRevenue.multiply(SUPPORTER_SHARE).setScale(10, RoundingMode.HALF_UP);
        }

        // Build and save the ad view record
        AdView adView = new AdView();
        adView.setUser(viewer);
        adView.setRevenueShare(totalRevenue);
        adView.setViewedAt(LocalDateTime.now());

        // Set referrer (uses the referred_artist_id column — works for any user role)
        if (referrerId != null) {
            userRepository.findById(referrerId).ifPresent(adView::setReferredArtist);
        }

        // Set supported artist
        if (supportedArtistId != null) {
            userRepository.findById(supportedArtistId).ifPresent(adView::setSupportedArtist);
        }

        return adViewRepository.save(adView);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EARNINGS SUMMARY — For the dashboard
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get complete earnings summary for any user (listener or artist).
     * Returns all revenue streams broken down.
     */
    public Map<String, Object> getEarningsSummary(UUID userId) {
        Map<String, Object> summary = new HashMap<>();

        LocalDate now = LocalDate.now();
        LocalDate monthStart = now.withDayOfMonth(1);
        LocalDateTime monthStartTime = monthStart.atStartOfDay();

        // ── Referral earnings (ALL users earn this) ──
        BigDecimal lifetimeReferralEarnings = safeSum(
                adViewRepository.sumReferralEarnings(userId));
        BigDecimal monthlyReferralEarnings = safeSum(
                adViewRepository.sumReferralEarningsSince(userId, monthStartTime));

        // ── Supporter earnings (Artists only) ──
        BigDecimal lifetimeSupporterEarnings = safeSum(
                adViewRepository.sumSupporterEarnings(userId));
        BigDecimal monthlySupporterEarnings = safeSum(
                adViewRepository.sumSupporterEarningsSince(userId, monthStartTime));

        // Apply the actual percentage shares
        BigDecimal referralLifetime = lifetimeReferralEarnings.multiply(REFERRER_SHARE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal referralMonthly = monthlyReferralEarnings.multiply(REFERRER_SHARE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal supporterLifetime = lifetimeSupporterEarnings.multiply(SUPPORTER_SHARE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal supporterMonthly = monthlySupporterEarnings.multiply(SUPPORTER_SHARE).setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalLifetime = referralLifetime.add(supporterLifetime);
        BigDecimal totalMonthly = referralMonthly.add(supporterMonthly);

        // ── Referral count ──
        long referralCount = referralRepository.countByReferrer(userId);

        // ── Supporter count (for artists) ──
        long supporterCount = userRepository.countBySupportedArtistId(userId);

        // ── Ad views generated by user's referrals this month ──
        long referralViewsThisMonth = adViewRepository.countReferralViewsSince(userId, monthStartTime);

        // ── Payout info ──
        BigDecimal payoutThreshold = new BigDecimal("50.00");
        BigDecimal currentBalance = totalLifetime; // Simplified — in production, subtract past payouts

        summary.put("referralEarnings", Map.of(
                "lifetime", referralLifetime,
                "thisMonth", referralMonthly
        ));
        summary.put("supporterEarnings", Map.of(
                "lifetime", supporterLifetime,
                "thisMonth", supporterMonthly
        ));
        summary.put("totalEarnings", Map.of(
                "lifetime", totalLifetime,
                "thisMonth", totalMonthly
        ));
        summary.put("referralCount", referralCount);
        summary.put("supporterCount", supporterCount);
        summary.put("referralViewsThisMonth", referralViewsThisMonth);
        summary.put("currentBalance", currentBalance);
        summary.put("payoutThreshold", payoutThreshold);
        summary.put("payoutReady", currentBalance.compareTo(payoutThreshold) >= 0);
        summary.put("cpm", CPM);

        return summary;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REFERRAL BREAKDOWN — Show each referral's contribution
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get a list of the user's referrals with each one's ad view count
     * and estimated earnings contribution.
     */
    public List<Map<String, Object>> getReferralBreakdown(UUID userId) {
        List<Object[]> referralData = adViewRepository.getReferralContributions(userId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : referralData) {
            UUID referredUserId = (UUID) row[0];
            String username = (String) row[1];
            String photoUrl = (String) row[2];
            Long viewCount = ((Number) row[3]).longValue();

            BigDecimal earnings = REVENUE_PER_VIEW
                    .multiply(REFERRER_SHARE)
                    .multiply(new BigDecimal(viewCount))
                    .setScale(4, RoundingMode.HALF_UP);

            Map<String, Object> entry = new HashMap<>();
            entry.put("userId", referredUserId);
            entry.put("username", username);
            entry.put("photoUrl", photoUrl);
            entry.put("adViews", viewCount);
            entry.put("earnings", earnings);
            result.add(entry);
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DAILY HISTORY — For earnings chart
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get daily earnings for the last N days, broken down by source.
     */
    public List<Map<String, Object>> getDailyHistory(UUID userId, int days) {
        LocalDateTime startDate = LocalDate.now().minusDays(days).atStartOfDay();

        List<Object[]> referralDaily = adViewRepository.getDailyReferralEarnings(userId, startDate);
        List<Object[]> supporterDaily = adViewRepository.getDailySupporterEarnings(userId, startDate);

        // Merge into a single map keyed by date
        Map<LocalDate, Map<String, BigDecimal>> dailyMap = new TreeMap<>();

        for (Object[] row : referralDaily) {
            LocalDate date = ((java.sql.Date) row[0]).toLocalDate();
            BigDecimal total = (BigDecimal) row[1];
            BigDecimal earnings = total.multiply(REFERRER_SHARE).setScale(4, RoundingMode.HALF_UP);
            dailyMap.computeIfAbsent(date, k -> new HashMap<>()).put("referral", earnings);
        }

        for (Object[] row : supporterDaily) {
            LocalDate date = ((java.sql.Date) row[0]).toLocalDate();
            BigDecimal total = (BigDecimal) row[1];
            BigDecimal earnings = total.multiply(SUPPORTER_SHARE).setScale(4, RoundingMode.HALF_UP);
            dailyMap.computeIfAbsent(date, k -> new HashMap<>()).put("supporter", earnings);
        }

        // Convert to list
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<String, BigDecimal>> entry : dailyMap.entrySet()) {
            Map<String, Object> dayEntry = new HashMap<>();
            dayEntry.put("date", entry.getKey().toString());
            BigDecimal referral = entry.getValue().getOrDefault("referral", BigDecimal.ZERO);
            BigDecimal supporter = entry.getValue().getOrDefault("supporter", BigDecimal.ZERO);
            dayEntry.put("referralEarnings", referral);
            dayEntry.put("supporterEarnings", supporter);
            dayEntry.put("total", referral.add(supporter));
            result.add(dayEntry);
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private BigDecimal safeSum(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}