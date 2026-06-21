package com.unis.service;

import com.unis.entity.AdView;
import com.unis.entity.User;
import com.unis.repository.AdViewRepository;
import com.unis.repository.ReferralRepository;
import com.unis.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
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

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTANTS — Display Ad Revenue Split
    //
    // Per ad view, the total revenue (CPM/1000) is split:
    //   Supported Artist:  15%
    //   Level 1 Referrer:  10%
    //   Level 2 Referrer:   5%
    //   Level 3 Referrer:   2%
    //   Unis:              68% (base) + unclaimed referral shares
    //
    // Total always = 100%. If a referral level doesn't exist, that
    // share goes to Unis (not redistributed).
    // ═══════════════════════════════════════════════════════════════════════════

    private static final BigDecimal CPM = new BigDecimal("3.50");
    private static final BigDecimal REVENUE_PER_VIEW = CPM.divide(
            new BigDecimal("1000"), 10, RoundingMode.HALF_UP); // $0.0035

    private static final BigDecimal SUPPORTER_RATE = new BigDecimal("0.15");  // 15%
    private static final BigDecimal LEVEL1_RATE = new BigDecimal("0.10");     // 10%
    private static final BigDecimal LEVEL2_RATE = new BigDecimal("0.05");     // 5%
    private static final BigDecimal LEVEL3_RATE = new BigDecimal("0.02");     // 2%
    // Unis gets 68% base + any unclaimed referral shares

    // ═══════════════════════════════════════════════════════════════════════════
    // AD VIEW TRACKING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Track a display ad view with full 3-level referral attribution.
     *
     * 1. Look up viewer's supported artist → 15%
     * 2. Walk 3 levels up the referral chain → 10% / 5% / 2%
     * 3. Store all attributions on the ad_view record
     */
    public AdView trackAdView(UUID viewerUserId) {
        User viewer = userRepository.findById(viewerUserId)
                .orElseThrow(() -> new RuntimeException("User not found: " + viewerUserId));

        // Look up the 3-level referral chain in one query
        UUID level1Id = null;
        UUID level2Id = null;
        UUID level3Id = null;

        List<Object[]> chain = adViewRepository.findReferralChain(viewerUserId);
        if (chain != null && !chain.isEmpty()) {
            Object[] row = chain.get(0);
            level1Id = (UUID) row[0];
            level2Id = (UUID) row[1];
            level3Id = (UUID) row[2];
        }

        // Look up supported artist
        UUID supportedArtistId = viewer.getSupportedArtistId();



        // Build the ad view record
        AdView adView = new AdView();
        adView.setUser(viewer);
        adView.setRevenueShare(REVENUE_PER_VIEW);
        adView.setViewedAt(LocalDateTime.now());

        // Set supported artist (15%)
        if (supportedArtistId != null) {
            userRepository.findById(supportedArtistId)
                .filter(u -> Boolean.TRUE.equals(u.getPhoneVerified()))   
                .ifPresent(adView::setSupportedArtist);
        }

        // Set Level 1 referrer (10%)
        if (level1Id != null) {
            userRepository.findById(level1Id)
                .filter(u -> Boolean.TRUE.equals(u.getPhoneVerified()))  
                .ifPresent(adView::setReferredArtist);        
        }

        // Set Level 2 referrer (5%)
        if (level2Id != null) {
            userRepository.findById(level2Id)
                .filter(u -> Boolean.TRUE.equals(u.getPhoneVerified()))   
                .ifPresent(adView::setReferrerLevel2);          
        }

        // Set Level 3 referrer (2%)
        if (level3Id != null) {
            userRepository.findById(level3Id)
                .filter(u -> Boolean.TRUE.equals(u.getPhoneVerified()))   
                .ifPresent(adView::setReferrerLevel3);         
        }

        return adViewRepository.save(adView);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EARNINGS SUMMARY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Complete earnings summary for any user.
     * Calculates all revenue streams with correct percentages.
     */
    public Map<String, Object> getEarningsSummary(UUID userId) {
        Map<String, Object> summary = new HashMap<>();

        LocalDate now = LocalDate.now();
        LocalDateTime monthStart = now.withDayOfMonth(1).atStartOfDay();

        // ── Level 1 referral earnings (10%) ──
        BigDecimal l1Lifetime = safeSum(adViewRepository.sumLevel1ReferralEarnings(userId))
                .multiply(LEVEL1_RATE).setScale(6, RoundingMode.HALF_UP);
        BigDecimal l1Monthly = safeSum(adViewRepository.sumLevel1ReferralEarningsSince(userId, monthStart))
                .multiply(LEVEL1_RATE).setScale(6, RoundingMode.HALF_UP);

        // ── Level 2 referral earnings (5%) ──
        BigDecimal l2Lifetime = safeSum(adViewRepository.sumLevel2ReferralEarnings(userId))
                .multiply(LEVEL2_RATE).setScale(6, RoundingMode.HALF_UP);
        BigDecimal l2Monthly = safeSum(adViewRepository.sumLevel2ReferralEarningsSince(userId, monthStart))
                .multiply(LEVEL2_RATE).setScale(6, RoundingMode.HALF_UP);

        // ── Level 3 referral earnings (2%) ──
        BigDecimal l3Lifetime = safeSum(adViewRepository.sumLevel3ReferralEarnings(userId))
                .multiply(LEVEL3_RATE).setScale(6, RoundingMode.HALF_UP);
        BigDecimal l3Monthly = safeSum(adViewRepository.sumLevel3ReferralEarningsSince(userId, monthStart))
                .multiply(LEVEL3_RATE).setScale(6, RoundingMode.HALF_UP);

        // ── Total referral earnings (all 3 levels) ──
        BigDecimal referralLifetime = l1Lifetime.add(l2Lifetime).add(l3Lifetime);
        BigDecimal referralMonthly = l1Monthly.add(l2Monthly).add(l3Monthly);

        // ── Supporter earnings (15% — artists only) ──
        BigDecimal supporterLifetime = safeSum(adViewRepository.sumSupporterEarnings(userId))
                .multiply(SUPPORTER_RATE).setScale(6, RoundingMode.HALF_UP);
        BigDecimal supporterMonthly = safeSum(adViewRepository.sumSupporterEarningsSince(userId, monthStart))
                .multiply(SUPPORTER_RATE).setScale(6, RoundingMode.HALF_UP);

        // ── Totals ──
        BigDecimal totalLifetime = referralLifetime.add(supporterLifetime);
        BigDecimal totalMonthly = referralMonthly.add(supporterLifetime);

        // ── Counts ──
        long referralCount = referralRepository.countByReferrer(userId);
        long supporterCount = userRepository.countBySupportedArtistId(userId);
        long referralViewsThisMonth = adViewRepository.countLevel1ReferralViewsSince(userId, monthStart);

        // ── Payout ──
        BigDecimal payoutThreshold = new BigDecimal("50.00");
        BigDecimal currentBalance = totalLifetime; // Simplified — subtract past payouts in production

        summary.put("referralEarnings", Map.of(
                "lifetime", referralLifetime.setScale(4, RoundingMode.HALF_UP),
                "thisMonth", referralMonthly.setScale(4, RoundingMode.HALF_UP),
                "level1", Map.of("lifetime", l1Lifetime.setScale(4, RoundingMode.HALF_UP), "thisMonth", l1Monthly.setScale(4, RoundingMode.HALF_UP)),
                "level2", Map.of("lifetime", l2Lifetime.setScale(4, RoundingMode.HALF_UP), "thisMonth", l2Monthly.setScale(4, RoundingMode.HALF_UP)),
                "level3", Map.of("lifetime", l3Lifetime.setScale(4, RoundingMode.HALF_UP), "thisMonth", l3Monthly.setScale(4, RoundingMode.HALF_UP))
        ));
        summary.put("supporterEarnings", Map.of(
                "lifetime", supporterLifetime.setScale(4, RoundingMode.HALF_UP),
                "thisMonth", supporterMonthly.setScale(4, RoundingMode.HALF_UP)
        ));
        summary.put("totalEarnings", Map.of(
                "lifetime", totalLifetime.setScale(4, RoundingMode.HALF_UP),
                "thisMonth", totalMonthly.setScale(4, RoundingMode.HALF_UP)
        ));
        summary.put("referralCount", referralCount);
        summary.put("supporterCount", supporterCount);
        summary.put("referralViewsThisMonth", referralViewsThisMonth);
        summary.put("currentBalance", currentBalance.setScale(4, RoundingMode.HALF_UP));
        summary.put("payoutThreshold", payoutThreshold);
        summary.put("payoutReady", currentBalance.compareTo(payoutThreshold) >= 0);
        summary.put("cpm", CPM);

        return summary;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REFERRAL BREAKDOWN — Per-referral contribution (Level 1 direct only)
    // ═══════════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getReferralBreakdown(UUID userId) {
        List<Object[]> referralData = adViewRepository.getReferralContributions(userId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object[] row : referralData) {
            UUID referredUserId = (UUID) row[0];
            String username = (String) row[1];
            String photoUrl = (String) row[2];
            Long viewCount = ((Number) row[3]).longValue();

            // Level 1 earnings: each view × revenue_per_view × 10%
            BigDecimal earnings = REVENUE_PER_VIEW
                    .multiply(LEVEL1_RATE)
                    .multiply(new BigDecimal(viewCount))
                    .setScale(6, RoundingMode.HALF_UP);

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
    // DAILY HISTORY — For chart
    // ═══════════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getDailyHistory(UUID userId, int days) {
        LocalDateTime startDate = LocalDate.now().minusDays(days).atStartOfDay();

        // Get daily referral earnings with all 3 levels
        List<Object[]> referralDaily = adViewRepository.getDailyReferralEarningsAllLevels(userId, startDate);
        List<Object[]> supporterDaily = adViewRepository.getDailySupporterEarnings(userId, startDate);

        Map<LocalDate, Map<String, BigDecimal>> dailyMap = new TreeMap<>();

        for (Object[] row : referralDaily) {
            LocalDate date = ((java.sql.Date) row[0]).toLocalDate();
            BigDecimal l1Raw = (BigDecimal) row[1];
            BigDecimal l2Raw = (BigDecimal) row[2];
            BigDecimal l3Raw = (BigDecimal) row[3];

            BigDecimal l1 = l1Raw.multiply(LEVEL1_RATE).setScale(6, RoundingMode.HALF_UP);
            BigDecimal l2 = l2Raw.multiply(LEVEL2_RATE).setScale(6, RoundingMode.HALF_UP);
            BigDecimal l3 = l3Raw.multiply(LEVEL3_RATE).setScale(6, RoundingMode.HALF_UP);
            BigDecimal totalReferral = l1.add(l2).add(l3);

            dailyMap.computeIfAbsent(date, k -> new HashMap<>()).put("referral", totalReferral);
        }

        for (Object[] row : supporterDaily) {
            LocalDate date = ((java.sql.Date) row[0]).toLocalDate();
            BigDecimal total = (BigDecimal) row[1];
            BigDecimal earnings = total.multiply(SUPPORTER_RATE).setScale(6, RoundingMode.HALF_UP);
            dailyMap.computeIfAbsent(date, k -> new HashMap<>()).put("supporter", earnings);
        }

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