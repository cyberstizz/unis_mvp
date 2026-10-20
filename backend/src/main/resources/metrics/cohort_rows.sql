-- Cohort rows for one calendar month: for every signup month up to this one,
-- how many of its members were active this month, how much they listened,
-- and the money they have paid and earned Unis since signing up.
--   :first, :last      first and last day of the month (New York dates)
--   :mend              the month's end in the database clock
--   :dbtz              the database clock's time zone
--   :cohort_floor      signups before this date join the cohort of the month
--                      it falls in (launch day once launched)
WITH members AS (
    SELECT u.user_id, u.role,
           CAST(date_trunc('month', GREATEST(
               CAST((u.created_at AT TIME ZONE :dbtz AT TIME ZONE 'America/New_York') AS DATE),
               CAST(:cohort_floor AS DATE))) AS DATE) AS cohort_month,
           CASE WHEN u.signup_source = 'referral'
                  OR EXISTS (SELECT 1 FROM referrals r WHERE r.referred_id = u.user_id)
                THEN 'referred' ELSE 'not_referred' END AS referred
    FROM users u
    WHERE u.exclude_from_metrics = FALSE AND u.created_at < :mend
),
scoped AS (
    SELECT m.*,
           (EXTRACT(YEAR FROM CAST(:first AS DATE)) * 12 + EXTRACT(MONTH FROM CAST(:first AS DATE)))
         - (EXTRACT(YEAR FROM m.cohort_month) * 12 + EXTRACT(MONTH FROM m.cohort_month)) AS months_since
    FROM members m
),
activity AS (
    SELECT user_id, SUM(listen_secs) AS secs FROM user_active_days
    WHERE activity_date BETWEEN :first AND :last GROUP BY user_id
),
paid AS (
    SELECT payer_id, SUM(amount) AS gpv, SUM(COALESCE(platform_fee, 0)) AS fees FROM (
        SELECT buyer_id AS payer_id, amount, platform_fee FROM purchases WHERE status = 'completed' AND created_at < :mend
        UNION ALL
        SELECT supporter_id, amount, platform_fee FROM support_payments WHERE status = 'completed' AND created_at < :mend
    ) x GROUP BY payer_id
),
joined AS (
    SELECT s.cohort_month, s.months_since::int AS months_since, s.role, s.referred,
           (a.user_id IS NOT NULL) AS active,
           COALESCE(a.secs, 0) AS secs,
           COALESCE(p.gpv, 0) AS gpv,
           -- Fees only: Community Share income is paid per month, not per
           -- person, so it isn't split across cohorts.
           COALESCE(p.fees, 0) AS net
    FROM scoped s
    LEFT JOIN activity a ON a.user_id = s.user_id
    LEFT JOIN paid p     ON p.payer_id = s.user_id
    WHERE s.months_since >= 0
)
SELECT cohort_month, months_since, 'all' AS segment_type, 'all' AS segment_value,
       COUNT(*) AS cohort_size, COUNT(*) FILTER (WHERE active) AS active_users,
       ROUND(SUM(secs) / 3600.0, 2) AS listen_hours, SUM(gpv)::bigint AS gpv_cents, ROUND(SUM(net))::bigint AS net_revenue_cents
FROM joined GROUP BY cohort_month, months_since
UNION ALL
SELECT cohort_month, months_since, 'referred', referred,
       COUNT(*), COUNT(*) FILTER (WHERE active),
       ROUND(SUM(secs) / 3600.0, 2), SUM(gpv)::bigint, ROUND(SUM(net))::bigint
FROM joined GROUP BY cohort_month, months_since, referred
UNION ALL
SELECT cohort_month, months_since, 'role', role,
       COUNT(*), COUNT(*) FILTER (WHERE active),
       ROUND(SUM(secs) / 3600.0, 2), SUM(gpv)::bigint, ROUND(SUM(net))::bigint
FROM joined GROUP BY cohort_month, months_since, role
