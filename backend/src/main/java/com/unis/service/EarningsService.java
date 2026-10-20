package com.unis.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * What a person has earned on Unis, read from the earnings ledger.
 *
 * Supporter and referral money comes from the monthly Community Share
 * (CommunityShareService): once a month has been closed, everyone's share of
 * it is a row in earnings_ledger and counts toward their balance. Song sales
 * and support never pass through here; Stripe pays those straight into the
 * artist's account.
 *
 * Until November 2026 this class credited a share of a simulated $3.50 CPM on
 * every signed-in ad view. That model is gone: Google's ad rules forbid paying
 * anyone because ads were viewed, and those balances had no money behind them.
 * The old ad_views rows are kept for history and count for nothing.
 *
 * Balance = everything in the ledger − payouts made from the ledger
 * (completed or still processing). Payouts made before the change are marked
 * from_ledger = FALSE and don't count.
 */
@Service
public class EarningsService {

    private final JdbcTemplate jdbc;
    private final CommunityShareService communityShareService;

    public EarningsService(JdbcTemplate jdbc, CommunityShareService communityShareService) {
        this.jdbc = jdbc;
        this.communityShareService = communityShareService;
    }

    /** Smallest payout Stripe Connect sends (StripeConnectService uses the same $50). */
    public static final BigDecimal PAYOUT_THRESHOLD = new BigDecimal("50.00");

    private static final List<String> STREAMS = List.of("supporter", "referral_1", "referral_2", "referral_3", "adjustment");

    // ═══════════════════════════════════════════════════════════════════════════
    // EARNINGS SUMMARY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Everything the Earnings page and the artist dashboard show.
     *
     * Key names from the old summary are kept (referralEarnings, supporterEarnings,
     * totalEarnings, currentBalance, payoutThreshold, payoutReady, referralCount,
     * supporterCount, referralViewsThisMonth) so app builds that predate the
     * Community Share keep working. "thisMonth" is always 0: a month's money is
     * only known once it's closed. "lastMonth" is the most recent closed month.
     */
    public Map<String, Object> getEarningsSummary(UUID userId) {
        Map<String, BigDecimal> lifetime = sumByStream(userId, null);

        LocalDate lastClosed = jdbc.queryForObject(
                "SELECT MAX(period_month) FROM community_share_closes", LocalDate.class);
        Map<String, BigDecimal> last = lastClosed == null ? emptyStreams() : sumByStream(userId, lastClosed);

        BigDecimal l1 = lifetime.get("referral_1"), l2 = lifetime.get("referral_2"), l3 = lifetime.get("referral_3");
        BigDecimal referralLifetime = l1.add(l2).add(l3);
        BigDecimal referralLast = last.get("referral_1").add(last.get("referral_2")).add(last.get("referral_3"));
        BigDecimal supporterLifetime = lifetime.get("supporter");
        BigDecimal adjustmentsLifetime = lifetime.get("adjustment");
        BigDecimal totalLifetime = referralLifetime.add(supporterLifetime).add(adjustmentsLifetime);
        BigDecimal totalLast = referralLast.add(last.get("supporter")).add(last.get("adjustment"));

        BigDecimal available = getAvailableBalance(userId);
        BigDecimal paidOut = ledgerPayouts(userId, "completed");

        Map<String, Object> listening = communityShareService.listeningThisMonth(userId);
        @SuppressWarnings("unchecked")
        long level1People = ((Number) ((Map<String, Object>) listening.get("level1")).get("people")).longValue();
        @SuppressWarnings("unchecked")
        long level1Plays = ((Number) ((Map<String, Object>) listening.get("level1")).get("plays")).longValue();

        Long supporterCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE supported_artist_id = ? AND deleted_at IS NULL",
                Long.class, userId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("model", "community_share");
        summary.put("referralEarnings", Map.of(
                "lifetime", money(referralLifetime),
                "lastMonth", money(referralLast),
                "thisMonth", money(BigDecimal.ZERO),
                "level1", level(l1, last.get("referral_1")),
                "level2", level(l2, last.get("referral_2")),
                "level3", level(l3, last.get("referral_3"))));
        summary.put("supporterEarnings", Map.of(
                "lifetime", money(supporterLifetime),
                "lastMonth", money(last.get("supporter")),
                "thisMonth", money(BigDecimal.ZERO)));
        summary.put("adjustments", Map.of("lifetime", money(adjustmentsLifetime)));
        summary.put("totalEarnings", Map.of(
                "lifetime", money(totalLifetime),
                "lastMonth", money(totalLast),
                "thisMonth", money(BigDecimal.ZERO)));
        summary.put("lastClosedMonth", lastClosed == null ? null : YearMonth.from(lastClosed).toString());
        summary.put("listeningThisMonth", listening);
        summary.put("referralCount", level1People);
        summary.put("referralViewsThisMonth", level1Plays);   // old key; now counted plays by direct referrals
        summary.put("supporterCount", supporterCount == null ? 0 : supporterCount);
        summary.put("currentBalance", money(available));
        summary.put("lifetimePaidOut", money(paidOut));
        summary.put("payoutThreshold", PAYOUT_THRESHOLD);
        summary.put("payoutReady", available.compareTo(PAYOUT_THRESHOLD) >= 0);
        summary.put("rates", Map.of(
                "supporter", 15, "level1", 10, "level2", 5, "level3", 2,
                "dailyPlayCap", CommunityShareService.DAILY_PLAY_CAP));
        return summary;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REFERRALS — the people you invited directly
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Each direct referral: how much they've listened this month, and what
     * their listening has earned you at level 1 across closed months.
     */
    public List<Map<String, Object>> getReferralBreakdown(UUID userId) {
        Timestamp since = Timestamp.valueOf(CommunityShareService.serverRange(
                YearMonth.now(CommunityShareService.UNIS_ZONE))[0]);
        return jdbc.query("""
                SELECT r.referred_id, u.username, u.photo_url, (u.deleted_at IS NOT NULL) AS left_unis,
                       (SELECT COUNT(*) FROM song_plays sp
                         WHERE sp.user_id = r.referred_id AND sp.played_at >= ?) AS plays_this_month,
                       (SELECT COALESCE(SUM(l.amount), 0) FROM earnings_ledger l
                         WHERE l.user_id = ? AND l.source_user_id = r.referred_id AND l.stream = 'referral_1') AS earned
                FROM referrals r
                JOIN users u ON u.user_id = r.referred_id
                WHERE r.referrer_id = ?
                ORDER BY earned DESC, plays_this_month DESC, u.username
                """, (rs, i) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("userId", rs.getObject("referred_id", UUID.class));
            entry.put("username", rs.getString("username"));
            entry.put("photoUrl", rs.getString("photo_url"));
            entry.put("leftUnis", rs.getBoolean("left_unis"));
            entry.put("playsThisMonth", rs.getLong("plays_this_month"));
            entry.put("earnings", money(rs.getBigDecimal("earned")));
            return entry;
        }, since, userId, userId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MONTHLY HISTORY — for the chart
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The last {@code months} months, oldest first, including the current one
     * (which shows 0 until it's closed). Each entry:
     * {month, date, closed, referralEarnings, supporterEarnings, adjustments, total}.
     */
    public List<Map<String, Object>> getMonthlyHistory(UUID userId, int months) {
        int n = Math.max(1, Math.min(months, 24));
        YearMonth current = YearMonth.now(CommunityShareService.UNIS_ZONE);
        YearMonth first = current.minusMonths(n - 1L);

        Map<YearMonth, Map<String, BigDecimal>> byMonth = new HashMap<>();
        jdbc.query("""
                SELECT period_month, stream, SUM(amount) AS total
                FROM earnings_ledger
                WHERE user_id = ? AND period_month >= ?
                GROUP BY period_month, stream
                """, rs -> {
            YearMonth ym = YearMonth.from(rs.getDate("period_month").toLocalDate());
            byMonth.computeIfAbsent(ym, k -> new HashMap<>()).put(rs.getString("stream"), rs.getBigDecimal("total"));
        }, userId, Date.valueOf(first.atDay(1)));

        Set<YearMonth> closed = new HashSet<>();
        jdbc.query("SELECT period_month FROM community_share_closes WHERE period_month >= ?",
                rs -> { closed.add(YearMonth.from(rs.getDate("period_month").toLocalDate())); },
                Date.valueOf(first.atDay(1)));

        List<Map<String, Object>> result = new ArrayList<>();
        for (YearMonth ym = first; !ym.isAfter(current); ym = ym.plusMonths(1)) {
            Map<String, BigDecimal> s = byMonth.getOrDefault(ym, Map.of());
            BigDecimal referral = s.getOrDefault("referral_1", BigDecimal.ZERO)
                    .add(s.getOrDefault("referral_2", BigDecimal.ZERO))
                    .add(s.getOrDefault("referral_3", BigDecimal.ZERO));
            BigDecimal supporter = s.getOrDefault("supporter", BigDecimal.ZERO);
            BigDecimal adjustments = s.getOrDefault("adjustment", BigDecimal.ZERO);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("month", ym.toString());
            entry.put("date", ym.atDay(1).toString());
            entry.put("closed", closed.contains(ym));
            entry.put("referralEarnings", money(referral));
            entry.put("supporterEarnings", money(supporter));
            entry.put("adjustments", money(adjustments));
            entry.put("total", money(referral.add(supporter).add(adjustments)));
            result.add(entry);
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BALANCE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * What a user could be paid right now: everything in the ledger minus
     * payouts from the ledger that completed or are still processing. Used by
     * the payout request and by account deletion's $50 rule.
     */
    public BigDecimal getAvailableBalance(UUID userId) {
        BigDecimal earned = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM earnings_ledger WHERE user_id = ?",
                BigDecimal.class, userId);
        BigDecimal paid = ledgerPayouts(userId, "completed").add(ledgerPayouts(userId, "processing"));
        BigDecimal available = (earned == null ? BigDecimal.ZERO : earned).subtract(paid);
        return available.signum() < 0 ? BigDecimal.ZERO : available;
    }

    private BigDecimal ledgerPayouts(UUID userId, String status) {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payouts WHERE user_id = ? AND status = ? AND from_ledger = TRUE",
                BigDecimal.class, userId, status);
        return v == null ? BigDecimal.ZERO : v;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Ledger totals per stream: lifetime (month == null) or for one month. */
    private Map<String, BigDecimal> sumByStream(UUID userId, LocalDate month) {
        Map<String, BigDecimal> m = emptyStreams();
        String sql = "SELECT stream, COALESCE(SUM(amount), 0) AS total FROM earnings_ledger WHERE user_id = ?"
                + (month == null ? "" : " AND period_month = ?")
                + " GROUP BY stream";
        Object[] args = month == null ? new Object[] { userId } : new Object[] { userId, Date.valueOf(month) };
        jdbc.query(sql, rs -> { m.put(rs.getString("stream"), rs.getBigDecimal("total")); }, args);
        return m;
    }

    private static Map<String, BigDecimal> emptyStreams() {
        Map<String, BigDecimal> m = new HashMap<>();
        for (String s : STREAMS) m.put(s, BigDecimal.ZERO);
        return m;
    }

    private static Map<String, Object> level(BigDecimal lifetime, BigDecimal lastMonth) {
        return Map.of("lifetime", money(lifetime), "lastMonth", money(lastMonth), "thisMonth", money(BigDecimal.ZERO));
    }

    /** Four decimals: a listener's share can be a fraction of a cent, so the page doesn't round it to $0.00. */
    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(4, RoundingMode.HALF_UP);
    }
}
