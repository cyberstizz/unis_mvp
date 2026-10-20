package com.unis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Year-end tax report: what Unis paid each person in a tax year, split the way
 * the 1099 forms need it. Admin → Community Share → Tax year report.
 *
 * WHY UNIS NEEDS THIS: Unis pays Stripe's fees on its connected accounts
 * (controller.fees.payer = application), so Stripe does NOT file 1099s for
 * them; Unis does, using Stripe's 1099 tool (Dashboard → Connect → Tax
 * reporting). Two kinds of money go to people, on different forms:
 *
 *   • Community Share (supported-artist and referral shares, adjustments)
 *     — paid by Unis as transfers when someone withdraws. Usually Form
 *     1099-MISC (other income); filed for $2,000 or more in a year.
 *   • Song sales and direct support — card payments to the artist.
 *     Form 1099-K; filed for more than $20,000 AND more than 200 payments.
 *
 * Stripe's tool counts transfers to a connected account into 1099-K totals by
 * default, so each year the Community Share amounts from this report are moved
 * off the 1099-K and onto a 1099-MISC with Stripe's CSV import. Amounts are by
 * calendar year, by when the money moved (payouts by completed_at, payments by
 * created_at, both server time). This is bookkeeping, not tax advice: confirm
 * the forms with a tax advisor, as Stripe also recommends.
 *
 * Thresholds are the federal ones for tax year 2026. States can be lower.
 */
@Service
public class TaxReportService {

    private static final Logger log = LoggerFactory.getLogger(TaxReportService.class);

    public static final BigDecimal MISC_THRESHOLD = new BigDecimal("2000.00");
    public static final BigDecimal K_GROSS_THRESHOLD = new BigDecimal("20000.00");
    public static final int K_COUNT_THRESHOLD = 200;

    private final JdbcTemplate jdbc;

    public TaxReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String REPORT_SQL = """
            WITH cs_paid AS (
                SELECT user_id, SUM(amount) AS paid, COUNT(*) AS payouts
                FROM payouts
                WHERE from_ledger = TRUE AND status = 'completed'
                  AND completed_at >= ? AND completed_at < ?
                GROUP BY user_id
            ),
            credited AS (
                SELECT user_id,
                       SUM(CASE WHEN stream = 'supporter' THEN amount ELSE 0 END) AS supporter,
                       SUM(CASE WHEN stream LIKE 'referral%' THEN amount ELSE 0 END) AS referral,
                       SUM(CASE WHEN stream = 'adjustment' THEN amount ELSE 0 END) AS adjustment
                FROM earnings_ledger
                WHERE period_month >= ? AND period_month < ?
                GROUP BY user_id
            ),
            sales AS (
                SELECT artist_id AS user_id, COUNT(*) AS n, SUM(amount) AS gross, SUM(platform_fee) AS fees
                FROM purchases
                WHERE status = 'completed' AND created_at >= ? AND created_at < ?
                GROUP BY artist_id
            ),
            support AS (
                SELECT artist_id AS user_id, COUNT(*) AS n, SUM(amount) AS gross, SUM(platform_fee) AS fees
                FROM support_payments
                WHERE status = 'completed' AND created_at >= ? AND created_at < ?
                GROUP BY artist_id
            ),
            people AS (
                SELECT user_id FROM cs_paid UNION SELECT user_id FROM credited
                UNION SELECT user_id FROM sales UNION SELECT user_id FROM support
            )
            SELECT u.user_id, u.username, u.email, u.stripe_account_id, u.deleted_at,
                   COALESCE(cp.paid, 0)       AS cs_paid,
                   COALESCE(cp.payouts, 0)    AS cs_payouts,
                   COALESCE(cr.supporter, 0)  AS cr_supporter,
                   COALESCE(cr.referral, 0)   AS cr_referral,
                   COALESCE(cr.adjustment, 0) AS cr_adjustment,
                   COALESCE(sa.n, 0)          AS sales_n,
                   COALESCE(sa.gross, 0)      AS sales_gross_cents,
                   COALESCE(sa.fees, 0)       AS sales_fee_cents,
                   COALESCE(su.n, 0)          AS support_n,
                   COALESCE(su.gross, 0)      AS support_gross_cents,
                   COALESCE(su.fees, 0)       AS support_fee_cents
            FROM people p
            JOIN users u ON u.user_id = p.user_id
            LEFT JOIN cs_paid cp  ON cp.user_id = p.user_id
            LEFT JOIN credited cr ON cr.user_id = p.user_id
            LEFT JOIN sales sa    ON sa.user_id = p.user_id
            LEFT JOIN support su  ON su.user_id = p.user_id
            ORDER BY u.username
            """;

    /** One row per person who was paid or credited anything in the year, plus totals. */
    public Map<String, Object> report(int year) {
        int thisYear = Year.now().getValue();
        if (year < 2024 || year > thisYear) {
            throw new IllegalArgumentException("Pick a tax year from 2024 to " + thisYear + ".");
        }
        Timestamp from = Timestamp.valueOf(LocalDate.of(year, 1, 1).atStartOfDay());
        Timestamp to = Timestamp.valueOf(LocalDate.of(year + 1, 1, 1).atStartOfDay());
        java.sql.Date monthFrom = java.sql.Date.valueOf(LocalDate.of(year, 1, 1));
        java.sql.Date monthTo = java.sql.Date.valueOf(LocalDate.of(year + 1, 1, 1));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] totals = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        int[] counts = { 0, 0 };

        jdbc.query(REPORT_SQL, rs -> {
            BigDecimal csPaid = money(rs.getBigDecimal("cs_paid"));
            BigDecimal salesGross = cents(rs.getLong("sales_gross_cents"));
            BigDecimal salesFees = cents(rs.getLong("sales_fee_cents"));
            BigDecimal supportGross = cents(rs.getLong("support_gross_cents"));
            BigDecimal supportFees = cents(rs.getLong("support_fee_cents"));
            long salesN = rs.getLong("sales_n");
            long supportN = rs.getLong("support_n");
            BigDecimal paymentsGross = salesGross.add(supportGross);
            long paymentsN = salesN + supportN;

            boolean misc = csPaid.compareTo(MISC_THRESHOLD) >= 0;
            boolean k = paymentsGross.compareTo(K_GROSS_THRESHOLD) > 0 && paymentsN > K_COUNT_THRESHOLD;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", rs.getObject("user_id"));
            m.put("username", rs.getString("username"));
            m.put("email", rs.getString("email"));
            m.put("stripeAccountId", rs.getString("stripe_account_id"));
            m.put("accountClosed", rs.getTimestamp("deleted_at") != null);
            m.put("communitySharePaidOut", csPaid);
            m.put("communitySharePayouts", rs.getLong("cs_payouts"));
            m.put("supporterCredited", money(rs.getBigDecimal("cr_supporter")));
            m.put("referralCredited", money(rs.getBigDecimal("cr_referral")));
            m.put("adjustmentsCredited", money(rs.getBigDecimal("cr_adjustment")));
            m.put("salesCount", salesN);
            m.put("salesGross", salesGross);
            m.put("salesToArtist", salesGross.subtract(salesFees));
            m.put("supportCount", supportN);
            m.put("supportGross", supportGross);
            m.put("supportToArtist", supportGross.subtract(supportFees));
            m.put("paymentsGross", paymentsGross);
            m.put("paymentsCount", paymentsN);
            m.put("form1099Misc", misc);
            m.put("form1099K", k);
            rows.add(m);

            totals[0] = totals[0].add(csPaid);
            totals[1] = totals[1].add(salesGross);
            totals[2] = totals[2].add(supportGross);
            if (misc) counts[0]++;
            if (k) counts[1]++;
        }, from, to, monthFrom, monthTo, from, to, from, to);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("people", rows.size());
        summary.put("communitySharePaidOut", totals[0]);
        summary.put("salesGross", totals[1]);
        summary.put("supportGross", totals[2]);
        summary.put("needs1099Misc", counts[0]);
        summary.put("needs1099K", counts[1]);
        summary.put("miscThreshold", MISC_THRESHOLD);
        summary.put("kGrossThreshold", K_GROSS_THRESHOLD);
        summary.put("kCountThreshold", K_COUNT_THRESHOLD);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("year", year);
        body.put("summary", summary);
        body.put("rows", rows);
        log.info("[TaxReport] action=report year={} people={} misc={} k={}", year, rows.size(), counts[0], counts[1]);
        return body;
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal cents(long c) {
        return BigDecimal.valueOf(c, 2);
    }
}
