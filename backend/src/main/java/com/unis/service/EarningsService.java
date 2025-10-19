package com.unis.service;

import com.unis.repository.AdViewRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class EarningsService {
    @Autowired
    private AdViewRepository adViewRepository;

    private static final BigDecimal CPM = new BigDecimal("0.01");  // $0.01 per view (MVP)

    // Get earnings for artist (page 9; last 30 days)
    public List<Object[]> getEarningsByArtist(UUID artistId, int days) {
        LocalDate startDate = LocalDate.now().minusDays(days);
        return adViewRepository.getEarningsLastDays(artistId, startDate.atStartOfDay());
    }

    // Calculate daily total (views * CPM * shares)
    public BigDecimal getDailyEarnings(UUID artistId, LocalDate date) {
        BigDecimal supporterShare = adViewRepository.sumEarningsByDay(artistId, date).multiply(new BigDecimal("0.5"));  // 50% from supporters
        BigDecimal referralShare = adViewRepository.sumEarningsByDayFromReferrals(artistId, date).multiply(new BigDecimal("0.1"));  // 10% from referrals
        return supporterShare.add(referralShare);
    }

    // Breakdown by type (ad revenue/impressions)
    public Object getEarningsBreakdown(UUID artistId, int days) {
        List<Object[]> earningsData = getEarningsByArtist(artistId, days);
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal adRevenue = BigDecimal.ZERO;
        BigDecimal impressions = BigDecimal.ZERO;

        for (Object[] row : earningsData) {
            LocalDate date = (LocalDate) row[0];
            BigDecimal dailyTotal = (BigDecimal) row[1];
            total = total.add(dailyTotal);
            // Example breakdown: Assume 70% ad, 30% impressions (extend with real logic)
            adRevenue = adRevenue.add(dailyTotal.multiply(new BigDecimal("0.7")));
            impressions = impressions.add(dailyTotal.multiply(new BigDecimal("0.3")));
        }

        return new Object() {
            public BigDecimal total = total;
            public BigDecimal adRevenue = adRevenue;
            public BigDecimal impressions = impressions;
        };
    }
}