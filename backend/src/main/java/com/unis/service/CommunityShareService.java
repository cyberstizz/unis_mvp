package com.unis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * The Unis Community Share: how supported artists and referrers get paid.
 *
 * Each month, after that month's money has reached Unis, an admin closes the
 * month (Admin → Community Share). The close:
 *
 *   1. Adds up what Unis actually received for the month: ad income, other
 *      income (sponsors, Unis Plus, anything else) and Unis's fees on song
 *      sales and support.
 *   2. Divides it by counted listening. A counted play is one the player
 *      already counts (15 seconds / 25% rule, 30-minute cooldown). Only
 *      signed-in listeners count; plays of your own songs don't; and each
 *      listener counts at most {@link #DAILY_PLAY_CAP} plays a day, so nobody
 *      can farm it. If you did 1% of the month's counted listening, 1% of the
 *      month's revenue is "your part".
 *   3. Sends 15% of your part to the artist you supported when you played
 *      (stamped on each play), and 10 / 5 / 2% to the three people above you
 *      in the referral chain. Unis keeps the rest, including any share whose
 *      earner doesn't exist, has left, isn't phone-verified, or is a staff or
 *      test account.
 *
 * Nothing here looks at ads or ad views. That is the point: Google's AdSense
 * and AdMob rules forbid paying anyone because ads were viewed, so the money
 * follows listening, and ad income is just one of the things that fund it.
 *
 * Results are written to earnings_ledger (one row per earner, per listener,
 * per level) and community_share_closes (one row per month). Balances and
 * the Earnings page read from the ledger. Tables:
 * backend/sql/V2026_10h__community_share.sql
 */
@Service
public class CommunityShareService {

    private static final Logger log = LoggerFactory.getLogger(CommunityShareService.class);

    // The same four percentages Unis has always promised.
    public static final BigDecimal SUPPORTER_RATE = new BigDecimal("0.15");
    public static final BigDecimal LEVEL1_RATE    = new BigDecimal("0.10");
    public static final BigDecimal LEVEL2_RATE    = new BigDecimal("0.05");
    public static final BigDecimal LEVEL3_RATE    = new BigDecimal("0.02");

    /** Counted plays per listener per day that count toward the share (about 2.5 hours of music). */
    public static final int DAILY_PLAY_CAP = 50;

    /** Months and days follow Harlem's clock, like the voting periods. */
    public static final ZoneId UNIS_ZONE = ZoneId.of("America/New_York");

    /** Largest single amount an admin may enter, as a typo guard. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");

    private final JdbcTemplate jdbc;

    public CommunityShareService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Planning a close (no writes)
    // ═════════════════════════════════════════════════════════════════════════

    /** One earner's money from one listener at one level. */
    public record Entry(UUID earnerId, String stream, UUID sourceUserId, BigDecimal countedPlays, BigDecimal amount) {}

    /** Everything a close would do. */
    public record Plan(
            YearMonth month,
            BigDecimal adIncome,
            BigDecimal otherIncome,
            BigDecimal feeIncome,
            BigDecimal totalRevenue,
            BigDecimal countedPlays,
            int listeners,
            int earners,
            BigDecimal paidToCommunity,
            BigDecimal keptByUnis,
            Map<String, BigDecimal> byStream,
            List<Entry> entries) {

        /** For the admin preview: the totals, without thousands of rows. */
        public Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", month.toString());
            m.put("adIncome", adIncome);
            m.put("otherIncome", otherIncome);
            m.put("feeIncome", feeIncome);
            m.put("totalRevenue", totalRevenue);
            m.put("countedPlays", countedPlays.setScale(0, RoundingMode.HALF_UP));
            m.put("listeners", listeners);
            m.put("earners", earners);
            m.put("paidToCommunity", paidToCommunity.setScale(2, RoundingMode.HALF_UP));
            m.put("keptByUnis", keptByUnis.setScale(2, RoundingMode.HALF_UP));
            Map<String, BigDecimal> streams = new LinkedHashMap<>();
            byStream.forEach((k, v) -> streams.put(k, v.setScale(2, RoundingMode.HALF_UP)));
            m.put("byStream", streams);
            return m;
        }
    }

    private record Weight(UUID listenerId, UUID supportedArtistId, BigDecimal weight) {}

    private record Chain(UUID level1, UUID level2, UUID level3) {}

    // Counted plays per listener per supported artist, with the daily cap.
    // played_at is stored in the server's local time; it's converted to Harlem
    // time to find each play's day. If a listener changed artists mid-day, the
    // cap is shared between the two in proportion.
    private static final String WEIGHT_SQL = """
            WITH plays AS (
                SELECT sp.user_id, sp.supported_artist_id,
                       CAST((sp.played_at AT TIME ZONE CAST(? AS text)) AT TIME ZONE 'America/New_York' AS date) AS d
                FROM song_plays sp
                JOIN songs s ON s.song_id = sp.song_id
                JOIN users u ON u.user_id = sp.user_id
                WHERE sp.played_at >= ? AND sp.played_at < ?
                  AND s.artist_id IS DISTINCT FROM sp.user_id
                  AND u.exclude_from_metrics = FALSE
            ),
            grp AS (
                SELECT user_id, supported_artist_id, d, CAST(COUNT(*) AS numeric) AS n
                FROM plays GROUP BY user_id, supported_artist_id, d
            ),
            day_tot AS (
                SELECT user_id, d, SUM(n) AS tot FROM grp GROUP BY user_id, d
            )
            SELECT g.user_id, g.supported_artist_id,
                   SUM(CASE WHEN t.tot > CAST(? AS numeric) THEN g.n * CAST(? AS numeric) / t.tot ELSE g.n END) AS weight
            FROM grp g
            JOIN day_tot t ON t.user_id = g.user_id AND t.d = g.d
            GROUP BY g.user_id, g.supported_artist_id
            """;

    /**
     * Works out a month's Community Share without saving anything.
     *
     * @param month       a month that has fully ended (Harlem time)
     * @param adIncome    AdSense / AdMob money received for that month, in dollars
     * @param otherIncome sponsors, Unis Plus and anything else, in dollars
     * @param feeIncome   Unis's fees on song sales and support, in dollars
     *                    (null = use {@link #suggestedFeeIncome})
     */
    public Plan plan(YearMonth month, BigDecimal adIncome, BigDecimal otherIncome, BigDecimal feeIncome) {
        if (month == null) throw new IllegalArgumentException("Pick a month.");
        if (!month.isBefore(YearMonth.now(UNIS_ZONE))) {
            throw new IllegalArgumentException("A month can only be closed after it has ended.");
        }
        BigDecimal ad = money(adIncome, "Ad income");
        BigDecimal other = money(otherIncome, "Other income");
        BigDecimal fee = feeIncome == null ? suggestedFeeIncome(month) : money(feeIncome, "Fee income");
        BigDecimal total = ad.add(other).add(fee);

        LocalDateTime[] range = serverRange(month);
        List<Weight> weights = jdbc.query(WEIGHT_SQL, (rs, i) -> new Weight(
                rs.getObject("user_id", UUID.class),
                rs.getObject("supported_artist_id", UUID.class),
                rs.getBigDecimal("weight")),
                ZoneId.systemDefault().getId(), Timestamp.valueOf(range[0]), Timestamp.valueOf(range[1]),
                DAILY_PLAY_CAP, DAILY_PLAY_CAP);

        BigDecimal allPlays = BigDecimal.ZERO;
        for (Weight w : weights) allPlays = allPlays.add(w.weight());

        Set<UUID> canEarn = loadEarners();
        Map<UUID, Chain> chains = loadChains();

        // Each listener's part of the month's revenue.
        Map<UUID, BigDecimal> listenerPart = new LinkedHashMap<>();
        Map<UUID, BigDecimal> listenerPlays = new LinkedHashMap<>();
        List<Entry> entries = new ArrayList<>();

        if (allPlays.signum() > 0 && total.signum() > 0) {
            for (Weight w : weights) {
                BigDecimal part = total.multiply(w.weight()).divide(allPlays, 12, RoundingMode.DOWN);
                listenerPart.merge(w.listenerId(), part, BigDecimal::add);
                listenerPlays.merge(w.listenerId(), w.weight(), BigDecimal::add);

                UUID artist = w.supportedArtistId();
                if (artist != null && !artist.equals(w.listenerId()) && canEarn.contains(artist)) {
                    entries.add(new Entry(artist, "supporter", w.listenerId(),
                            w.weight(), cents6(part.multiply(SUPPORTER_RATE))));
                }
            }

            for (Map.Entry<UUID, BigDecimal> e : listenerPart.entrySet()) {
                UUID listener = e.getKey();
                Chain chain = chains.get(listener);
                if (chain == null) continue;
                BigDecimal part = e.getValue();
                BigDecimal plays = listenerPlays.get(listener);
                addReferral(entries, canEarn, chain.level1(), listener, "referral_1", LEVEL1_RATE, part, plays);
                addReferral(entries, canEarn, chain.level2(), listener, "referral_2", LEVEL2_RATE, part, plays);
                addReferral(entries, canEarn, chain.level3(), listener, "referral_3", LEVEL3_RATE, part, plays);
            }
        } else {
            for (Weight w : weights) listenerPart.merge(w.listenerId(), BigDecimal.ZERO, BigDecimal::add);
        }

        entries.removeIf(en -> en.amount().signum() <= 0);

        Map<String, BigDecimal> byStream = new LinkedHashMap<>();
        for (String s : List.of("supporter", "referral_1", "referral_2", "referral_3")) byStream.put(s, BigDecimal.ZERO);
        Set<UUID> earners = new HashSet<>();
        BigDecimal paid = BigDecimal.ZERO;
        for (Entry en : entries) {
            byStream.merge(en.stream(), en.amount(), BigDecimal::add);
            earners.add(en.earnerId());
            paid = paid.add(en.amount());
        }

        return new Plan(month, ad, other, fee, total, allPlays, listenerPart.size(), earners.size(),
                paid, total.subtract(paid), byStream, entries);
    }

    private static void addReferral(List<Entry> entries, Set<UUID> canEarn, UUID earner, UUID listener,
                                    String stream, BigDecimal rate, BigDecimal part, BigDecimal plays) {
        if (earner == null || earner.equals(listener) || !canEarn.contains(earner)) return;
        entries.add(new Entry(earner, stream, listener, plays, cents6(part.multiply(rate))));
    }

    /** Who can be paid: a live, phone-verified account that isn't a staff or test account. */
    private Set<UUID> loadEarners() {
        return new HashSet<>(jdbc.queryForList(
                "SELECT user_id FROM users WHERE deleted_at IS NULL AND phone_verified = TRUE "
                        + "AND exclude_from_metrics = FALSE", UUID.class));
    }

    /** Everyone's three referrers, read once. */
    private Map<UUID, Chain> loadChains() {
        Map<UUID, Chain> chains = new HashMap<>();
        jdbc.query("""
                SELECT r1.referred_id, r1.referrer_id AS l1, r2.referrer_id AS l2, r3.referrer_id AS l3
                FROM referrals r1
                LEFT JOIN referrals r2 ON r2.referred_id = r1.referrer_id
                LEFT JOIN referrals r3 ON r3.referred_id = r2.referrer_id
                """, rs -> {
            chains.putIfAbsent(rs.getObject("referred_id", UUID.class), new Chain(
                    rs.getObject("l1", UUID.class),
                    rs.getObject("l2", UUID.class),
                    rs.getObject("l3", UUID.class)));
        });
        return chains;
    }

    /**
     * Unis's fees on completed song sales and support during the month, as a
     * starting suggestion for the close form (the admin can change it, for
     * example to subtract Stripe's fees).
     */
    public BigDecimal suggestedFeeIncome(YearMonth month) {
        LocalDateTime[] range = serverRange(month);
        Long cents = jdbc.queryForObject("""
                SELECT COALESCE((SELECT SUM(platform_fee) FROM purchases
                                  WHERE status = 'completed' AND created_at >= ? AND created_at < ?), 0)
                     + COALESCE((SELECT SUM(platform_fee) FROM support_payments
                                  WHERE status = 'completed' AND created_at >= ? AND created_at < ?), 0)
                """, Long.class,
                Timestamp.valueOf(range[0]), Timestamp.valueOf(range[1]),
                Timestamp.valueOf(range[0]), Timestamp.valueOf(range[1]));
        return BigDecimal.valueOf(cents == null ? 0 : cents, 2);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Closing a month (writes)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Closes a month: saves the plan and credits everyone's balance. A month
     * can be closed once; the unique period_month stops a double close even if
     * two admins press the button together.
     */
    @Transactional
    public Plan close(YearMonth month, BigDecimal adIncome, BigDecimal otherIncome, BigDecimal feeIncome,
                      String note, UUID adminId) {
        if (isClosed(month)) {
            throw new IllegalStateException(month + " is already closed.");
        }
        Plan plan = plan(month, adIncome, otherIncome, feeIncome);
        LocalDate period = month.atDay(1);

        UUID closeId = jdbc.queryForObject("""
                INSERT INTO community_share_closes
                    (period_month, ad_income, other_income, fee_income, total_revenue, counted_plays,
                     listeners, earners, paid_to_community, kept_by_unis, note, closed_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING close_id
                """, UUID.class,
                Date.valueOf(period), plan.adIncome(), plan.otherIncome(), plan.feeIncome(), plan.totalRevenue(),
                plan.countedPlays().setScale(2, RoundingMode.HALF_UP), plan.listeners(), plan.earners(),
                plan.paidToCommunity(), plan.keptByUnis(), blankToNull(note), adminId);

        List<Object[]> rows = new ArrayList<>(plan.entries().size());
        for (Entry e : plan.entries()) {
            rows.add(new Object[] {
                    e.earnerId(), Date.valueOf(period), e.stream(), e.sourceUserId(),
                    e.countedPlays().setScale(2, RoundingMode.HALF_UP), e.amount(), closeId });
        }
        jdbc.batchUpdate("""
                INSERT INTO earnings_ledger
                    (user_id, period_month, stream, source_user_id, counted_plays, amount, close_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, rows);

        log.info("[CommunityShare] action=close month={} revenue={} plays={} listeners={} earners={} paid={} kept={} by={}",
                month, plan.totalRevenue(), plan.countedPlays().setScale(0, RoundingMode.HALF_UP),
                plan.listeners(), plan.earners(), plan.paidToCommunity().setScale(2, RoundingMode.HALF_UP),
                plan.keptByUnis().setScale(2, RoundingMode.HALF_UP), adminId);
        return plan;
    }

    public boolean isClosed(YearMonth month) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM community_share_closes WHERE period_month = ?",
                Integer.class, Date.valueOf(month.atDay(1)));
        return n != null && n > 0;
    }

    /** Every closed month, newest first. */
    public List<Map<String, Object>> listCloses() {
        return jdbc.query("""
                SELECT c.close_id, c.period_month, c.ad_income, c.other_income, c.fee_income, c.total_revenue,
                       c.counted_plays, c.listeners, c.earners, c.paid_to_community, c.kept_by_unis, c.note,
                       c.closed_at, u.username AS closed_by
                FROM community_share_closes c
                LEFT JOIN users u ON u.user_id = c.closed_by
                ORDER BY c.period_month DESC
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("closeId", rs.getObject("close_id", UUID.class));
            m.put("month", YearMonth.from(rs.getDate("period_month").toLocalDate()).toString());
            m.put("adIncome", rs.getBigDecimal("ad_income"));
            m.put("otherIncome", rs.getBigDecimal("other_income"));
            m.put("feeIncome", rs.getBigDecimal("fee_income"));
            m.put("totalRevenue", rs.getBigDecimal("total_revenue"));
            m.put("countedPlays", rs.getBigDecimal("counted_plays").setScale(0, RoundingMode.HALF_UP));
            m.put("listeners", rs.getInt("listeners"));
            m.put("earners", rs.getInt("earners"));
            m.put("paidToCommunity", rs.getBigDecimal("paid_to_community").setScale(2, RoundingMode.HALF_UP));
            m.put("keptByUnis", rs.getBigDecimal("kept_by_unis").setScale(2, RoundingMode.HALF_UP));
            m.put("note", rs.getString("note"));
            m.put("closedAt", rs.getTimestamp("closed_at").toLocalDateTime().toString());
            m.put("closedBy", rs.getString("closed_by"));
            return m;
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Manual adjustments (super admin)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Credits (or, with a negative amount, takes back) money on one person's
     * balance, with a reason that's kept forever. For corrections and the
     * live-money payout test, not for regular pay.
     */
    @Transactional
    public Map<String, Object> addAdjustment(String username, BigDecimal amount, String note, UUID adminId) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Enter a username.");
        if (amount == null || amount.signum() == 0) throw new IllegalArgumentException("Enter an amount other than zero.");
        if (amount.abs().compareTo(new BigDecimal("10000")) > 0) {
            throw new IllegalArgumentException("Adjustments are limited to $10,000.");
        }
        if (note == null || note.isBlank()) throw new IllegalArgumentException("Write down why.");

        List<UUID> ids = jdbc.queryForList(
                "SELECT user_id FROM users WHERE LOWER(username) = LOWER(?) AND deleted_at IS NULL",
                UUID.class, username.trim());
        if (ids.isEmpty()) throw new IllegalArgumentException("No active account is called " + username.trim() + ".");
        UUID userId = ids.get(0);

        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        UUID entryId = jdbc.queryForObject("""
                INSERT INTO earnings_ledger (user_id, period_month, stream, amount, note, created_by)
                VALUES (?, ?, 'adjustment', ?, ?, ?)
                RETURNING entry_id
                """, UUID.class,
                userId, Date.valueOf(YearMonth.now(UNIS_ZONE).atDay(1)), value, note.trim(), adminId);

        log.info("[CommunityShare] action=adjustment user={} amount={} by={}", userId, value, adminId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entryId", entryId);
        m.put("userId", userId);
        m.put("username", username.trim());
        m.put("amount", value);
        return m;
    }

    public List<Map<String, Object>> listAdjustments(int limit) {
        return jdbc.query("""
                SELECT l.entry_id, l.amount, l.note, l.created_at, u.username, a.username AS created_by
                FROM earnings_ledger l
                JOIN users u ON u.user_id = l.user_id
                LEFT JOIN users a ON a.user_id = l.created_by
                WHERE l.stream = 'adjustment'
                ORDER BY l.created_at DESC
                LIMIT ?
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entryId", rs.getObject("entry_id", UUID.class));
            m.put("username", rs.getString("username"));
            m.put("amount", rs.getBigDecimal("amount").setScale(2, RoundingMode.HALF_UP));
            m.put("note", rs.getString("note"));
            m.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString());
            m.put("createdBy", rs.getString("created_by"));
            return m;
        }, Math.max(1, Math.min(limit, 200)));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Live counters for the Earnings page
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * This month so far, before it's closed: how much your network and your
     * supporters have listened, and how many people are in each level.
     * Plays here aren't capped or filtered yet; the close does that.
     */
    public Map<String, Object> listeningThisMonth(UUID userId) {
        LocalDateTime start = serverRange(YearMonth.now(UNIS_ZONE))[0];
        Timestamp since = Timestamp.valueOf(start);

        Map<Integer, long[]> byLevel = new HashMap<>(); // level -> {people, plays}
        jdbc.query("""
                WITH net AS (
                    SELECT r1.referred_id AS uid, 1 AS lvl FROM referrals r1 WHERE r1.referrer_id = ?
                    UNION ALL
                    SELECT r2.referred_id, 2 FROM referrals r1
                      JOIN referrals r2 ON r2.referrer_id = r1.referred_id WHERE r1.referrer_id = ?
                    UNION ALL
                    SELECT r3.referred_id, 3 FROM referrals r1
                      JOIN referrals r2 ON r2.referrer_id = r1.referred_id
                      JOIN referrals r3 ON r3.referrer_id = r2.referred_id WHERE r1.referrer_id = ?
                )
                SELECT n.lvl, COUNT(DISTINCT n.uid) AS people, COUNT(sp.play_id) AS plays
                FROM net n
                LEFT JOIN song_plays sp ON sp.user_id = n.uid AND sp.played_at >= ?
                GROUP BY n.lvl
                """, rs -> {
            byLevel.put(rs.getInt("lvl"), new long[] { rs.getLong("people"), rs.getLong("plays") });
        }, userId, userId, userId, since);

        Long supporterPlays = jdbc.queryForObject(
                "SELECT COUNT(*) FROM song_plays WHERE supported_artist_id = ? AND user_id <> ? AND played_at >= ?",
                Long.class, userId, userId, since);

        Map<String, Object> m = new LinkedHashMap<>();
        long totalPlays = 0;
        for (int lvl = 1; lvl <= 3; lvl++) {
            long[] v = byLevel.getOrDefault(lvl, new long[] { 0, 0 });
            m.put("level" + lvl, Map.of("people", v[0], "plays", v[1]));
            totalPlays += v[1];
        }
        m.put("networkPlays", totalPlays);
        m.put("supporterPlays", supporterPlays == null ? 0 : supporterPlays);
        m.put("month", YearMonth.now(UNIS_ZONE).toString());
        return m;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    /** A Harlem calendar month as [start, end) in the server's local time (how played_at is stored). */
    public static LocalDateTime[] serverRange(YearMonth month) {
        ZoneId server = ZoneId.systemDefault();
        LocalDateTime start = month.atDay(1).atStartOfDay(UNIS_ZONE).withZoneSameInstant(server).toLocalDateTime();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay(UNIS_ZONE).withZoneSameInstant(server).toLocalDateTime();
        return new LocalDateTime[] { start, end };
    }

    private static BigDecimal money(BigDecimal v, String label) {
        if (v == null) return BigDecimal.ZERO.setScale(2);
        if (v.signum() < 0) throw new IllegalArgumentException(label + " can't be negative.");
        if (v.compareTo(MAX_AMOUNT) > 0) throw new IllegalArgumentException(label + " looks too large. Check the number.");
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Six decimal places, rounded down so the total paid never exceeds what came in. */
    private static BigDecimal cents6(BigDecimal v) {
        return v.setScale(6, RoundingMode.DOWN);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
