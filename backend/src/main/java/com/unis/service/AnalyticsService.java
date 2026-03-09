package com.unis.service;

import com.unis.repository.DmcaClaimRepository;
import com.unis.repository.UserActivityRepository;
import com.unis.repository.UserRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.SongPlayRepository;
import com.unis.repository.AccountSuspensionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    @Autowired
    private UserActivityRepository userActivityRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private SongPlayRepository songPlayRepository;

    @Autowired
    private DmcaClaimRepository dmcaClaimRepository;

    @Autowired
    private AccountSuspensionRepository accountSuspensionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Dashboard overview — combined summary for the home page
     */
    public Map<String, Object> getOverview() {
        Map<String, Object> overview = new HashMap<>();

        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        LocalDateTime startOfYesterday = LocalDate.now().minusDays(1).atStartOfDay();
        LocalDateTime endOfToday = LocalDate.now().atTime(LocalTime.MAX);
        LocalDateTime endOfYesterday = LocalDate.now().minusDays(1).atTime(LocalTime.MAX);

        // Total counts
        overview.put("totalUsers", userRepository.count());
        overview.put("totalArtists", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'artist' AND deleted_at IS NULL", Long.class));
        overview.put("totalSongs", songRepository.count());

        // DAU today vs yesterday
        long dauToday = userActivityRepository.countActiveUsersForDay(startOfToday, endOfToday);
        long dauYesterday = userActivityRepository.countActiveUsersForDay(startOfYesterday, endOfYesterday);
        overview.put("dauToday", dauToday);
        overview.put("dauYesterday", dauYesterday);

        // New signups today
        overview.put("signupsToday", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE created_at >= ? AND deleted_at IS NULL",
                Long.class, startOfToday));

        // Plays today
        overview.put("playsToday", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM song_plays WHERE played_at >= ?",
                Long.class, startOfToday));

        // Open DMCA claims
        overview.put("openDmcaClaims", dmcaClaimRepository.countByStatus("submitted") +
                dmcaClaimRepository.countByStatus("reviewing"));

        // Active suspensions
        overview.put("activeSuspensions", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_suspensions WHERE lifted_at IS NULL " +
                "AND (expires_at IS NULL OR expires_at > NOW())", Long.class));

        return overview;
    }

    /**
     * Daily active users for a date range
     */
    public Map<String, Long> getDailyActiveUsers(LocalDate startDate, LocalDate endDate) {
        List<Object[]> results = userActivityRepository.getDailyActiveUsers(
                startDate.atStartOfDay(),
                endDate.plusDays(1).atStartOfDay());

        Map<String, Long> dau = new LinkedHashMap<>();
        for (Object[] row : results) {
            String day = row[0].toString();
            Long count = ((Number) row[1]).longValue();
            dau.put(day, count);
        }
        return dau;
    }

    /**
     * Monthly active users for last N months
     */
    public Map<String, Long> getMonthlyActiveUsers(int months) {
        LocalDateTime since = LocalDate.now().minusMonths(months).atStartOfDay();
        List<Object[]> results = userActivityRepository.getMonthlyActiveUsers(since);

        Map<String, Long> mau = new LinkedHashMap<>();
        for (Object[] row : results) {
            String month = row[0].toString();
            Long count = ((Number) row[1]).longValue();
            mau.put(month, count);
        }
        return mau;
    }

    /**
     * New signups by date
     */
    public Map<String, Long> getNewSignups(LocalDate startDate, LocalDate endDate) {
        String sql = "SELECT DATE(created_at) as day, COUNT(*) as count FROM users " +
                     "WHERE created_at >= ? AND created_at < ? AND deleted_at IS NULL " +
                     "GROUP BY DATE(created_at) ORDER BY day";

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql,
                startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());

        Map<String, Long> signups = new LinkedHashMap<>();
        for (Map<String, Object> row : results) {
            signups.put(row.get("day").toString(), ((Number) row.get("count")).longValue());
        }
        return signups;
    }

    /**
     * Play counts by date
     */
    public Map<String, Long> getPlayCounts(LocalDate startDate, LocalDate endDate) {
        String sql = "SELECT DATE(played_at) as day, COUNT(*) as count FROM song_plays " +
                     "WHERE played_at >= ? AND played_at < ? " +
                     "GROUP BY DATE(played_at) ORDER BY day";

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql,
                startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());

        Map<String, Long> plays = new LinkedHashMap<>();
        for (Map<String, Object> row : results) {
            plays.put(row.get("day").toString(), ((Number) row.get("count")).longValue());
        }
        return plays;
    }

    /**
     * Vote activity grouped by jurisdiction
     */
    public Map<String, Long> getVotesByJurisdiction(LocalDate startDate, LocalDate endDate) {
        String sql = "SELECT j.name, COUNT(v.*) as count FROM votes v " +
                     "JOIN jurisdictions j ON v.jurisdiction_id = j.jurisdiction_id " +
                     "WHERE v.vote_date >= ? AND v.vote_date <= ? " +
                     "GROUP BY j.name ORDER BY count DESC";

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, startDate, endDate);

        Map<String, Long> votes = new LinkedHashMap<>();
        for (Map<String, Object> row : results) {
            votes.put((String) row.get("name"), ((Number) row.get("count")).longValue());
        }
        return votes;
    }

    /**
     * Referral stats
     */
    public Map<String, Object> getReferralStats() {
        Map<String, Object> stats = new HashMap<>();

        // Total referrals
        stats.put("totalReferrals", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM referrals", Long.class));

        // Max chain depth (recursive)
        String depthQuery = """
            WITH RECURSIVE chain AS (
                SELECT referred_id, referrer_id, 1 as depth
                FROM referrals
                UNION ALL
                SELECT r.referred_id, r.referrer_id, c.depth + 1
                FROM referrals r
                JOIN chain c ON r.referrer_id = c.referred_id
                WHERE c.depth < 20
            )
            SELECT COALESCE(MAX(depth), 0) FROM chain
            """;
        stats.put("maxChainDepth", jdbcTemplate.queryForObject(depthQuery, Integer.class));

        // Top 10 referrers
        String topReferrersQuery = """
            SELECT u.username, COUNT(r.referral_id) as referral_count
            FROM referrals r
            JOIN users u ON r.referrer_id = u.user_id
            GROUP BY u.username
            ORDER BY referral_count DESC
            LIMIT 10
            """;
        List<Map<String, Object>> topReferrers = jdbcTemplate.queryForList(topReferrersQuery);
        stats.put("topReferrers", topReferrers);

        // Referrals per day (last 30 days)
        String dailyQuery = """
            SELECT DATE(created_at) as day, COUNT(*) as count
            FROM referrals
            WHERE created_at >= NOW() - INTERVAL '30 days'
            GROUP BY DATE(created_at)
            ORDER BY day
            """;
        stats.put("dailyReferrals", jdbcTemplate.queryForList(dailyQuery));

        return stats;
    }

    /**
     * DMCA statistics
     */
    public Map<String, Object> getDmcaStats() {
        Map<String, Object> stats = new HashMap<>();

        // Claims by status
        Map<String, Long> byStatus = new LinkedHashMap<>();
        byStatus.put("submitted", dmcaClaimRepository.countByStatus("submitted"));
        byStatus.put("reviewing", dmcaClaimRepository.countByStatus("reviewing"));
        byStatus.put("upheld", dmcaClaimRepository.countByStatus("upheld"));
        byStatus.put("rejected", dmcaClaimRepository.countByStatus("rejected"));
        byStatus.put("counter_pending", dmcaClaimRepository.countByStatus("counter_pending"));
        byStatus.put("resolved", dmcaClaimRepository.countByStatus("resolved"));
        stats.put("claimsByStatus", byStatus);

        // Average resolution time (last 6 months)
        LocalDateTime sixMonthsAgo = LocalDateTime.now().minusMonths(6);
        Double avgDays = dmcaClaimRepository.averageResolutionDays(sixMonthsAgo);
        stats.put("averageResolutionDays", avgDays != null ? avgDays : 0.0);

        // Claims per month (last 6 months)
        String monthlyQuery = """
            SELECT TO_CHAR(created_at, 'YYYY-MM') as month, COUNT(*) as count
            FROM dmca_claims
            WHERE created_at >= NOW() - INTERVAL '6 months'
            GROUP BY TO_CHAR(created_at, 'YYYY-MM')
            ORDER BY month
            """;
        stats.put("monthlyClaimVolume", jdbcTemplate.queryForList(monthlyQuery));

        return stats;
    }
}