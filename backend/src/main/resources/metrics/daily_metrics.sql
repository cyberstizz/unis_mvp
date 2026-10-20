-- One row per metric (and segment) for day :d. Reads the ledger written by
-- daily_ledger.sql for this day and the 29 days before it.
--   :d, :dstart, :dend  the day and its bounds in the database clock
--   :wstart             first day of the rolling 30-day window (never before launch day once launched)
--   :suspicious         daily counted plays above which an account is flagged for review
WITH day_rows AS (
    SELECT * FROM user_active_days WHERE activity_date = :d
),
win AS (
    SELECT * FROM user_active_days WHERE activity_date BETWEEN :wstart AND :d
),
latest AS (
    SELECT DISTINCT ON (user_id) user_id, role, area_id, age_band, gender
    FROM win ORDER BY user_id, activity_date DESC
),
cp AS (
    SELECT sp.play_id, sp.completed, sp.source, sp.platform,
           sp.listener_jurisdiction_id, s.jurisdiction_id AS song_area
    FROM song_plays sp
    JOIN songs s ON s.song_id = sp.song_id
    JOIN day_rows a ON a.user_id = sp.user_id
    WHERE sp.played_at >= :dstart AND sp.played_at < :dend
      AND s.artist_id <> sp.user_id
),
anon AS (
    SELECT * FROM anonymous_plays WHERE played_at >= :dstart AND played_at < :dend
),
pay AS (
    SELECT p.amount, p.platform_fee FROM purchases p JOIN users u ON u.user_id = p.buyer_id
    WHERE p.created_at >= :dstart AND p.created_at < :dend AND p.status = 'completed' AND u.exclude_from_metrics = FALSE
    UNION ALL
    SELECT s.amount, s.platform_fee FROM support_payments s JOIN users u ON u.user_id = s.supporter_id
    WHERE s.created_at >= :dstart AND s.created_at < :dend AND s.status = 'completed' AND u.exclude_from_metrics = FALSE
)
INSERT INTO metric_daily (metric_date, metric_key, segment_type, segment_value, value)
SELECT CAST(:d AS DATE), k, st, sv, v FROM (
    SELECT 'dau' AS k, 'all' AS st, 'all' AS sv, COUNT(*)::numeric AS v FROM day_rows
    UNION ALL
    SELECT 'dau', 'platform', p, COUNT(*) FROM day_rows, unnest(string_to_array(platforms, ',')) AS p GROUP BY p
    UNION ALL
    SELECT 'dau', 'role', COALESCE(role, 'unknown'), COUNT(*) FROM day_rows GROUP BY role
    UNION ALL
    SELECT 'mau', 'all', 'all', COUNT(*) FROM latest
    UNION ALL
    SELECT 'mau', 'role', COALESCE(role, 'unknown'), COUNT(*) FROM latest GROUP BY role
    UNION ALL
    SELECT 'mau', 'area', COALESCE(area_id::text, 'none'), COUNT(*) FROM latest WHERE age_band <> '13-17' GROUP BY area_id
    UNION ALL
    SELECT 'mal', 'all', 'all', COUNT(DISTINCT user_id) FROM win WHERE played
    UNION ALL
    SELECT 'voters_30d', 'all', 'all', COUNT(DISTINCT user_id) FROM win WHERE voted
    UNION ALL
    SELECT 'listeners', 'all', 'all', COUNT(*) FROM day_rows WHERE played
    UNION ALL
    SELECT 'plays', 'all', 'all', COALESCE(SUM(plays), 0) FROM day_rows
    UNION ALL
    SELECT 'listen_secs', 'all', 'all', COALESCE(SUM(listen_secs), 0) FROM day_rows
    UNION ALL
    SELECT 'plays', 'platform', COALESCE(platform, 'unknown'), COUNT(*) FROM cp GROUP BY platform
    UNION ALL
    SELECT 'completed_plays', 'all', 'all', COUNT(*) FROM cp WHERE completed
    UNION ALL
    SELECT 'local_plays', 'all', 'all', COUNT(*) FROM cp
    JOIN metric_local_area la ON la.jurisdiction_id = cp.listener_jurisdiction_id
    JOIN metric_local_area ls ON ls.jurisdiction_id = cp.song_area
    WHERE la.local_area_id = ls.local_area_id
    UNION ALL
    SELECT 'suspicious_plays', 'all', 'all', COALESCE(SUM(plays), 0) FROM day_rows WHERE plays > :suspicious
    UNION ALL
    SELECT 'voters', 'all', 'all', COUNT(*) FROM day_rows WHERE voted
    UNION ALL
    SELECT 'messagers', 'all', 'all', COUNT(*) FROM day_rows WHERE messaged
    UNION ALL
    SELECT 'payers', 'all', 'all', COUNT(*) FROM day_rows WHERE paid
    UNION ALL
    SELECT 'anon_plays', 'all', 'all', COUNT(*) FROM anon
    UNION ALL
    SELECT 'anon_visitors', 'all', 'all', COUNT(DISTINCT visitor_id) FROM anon
    UNION ALL
    SELECT 'anon_listen_secs', 'all', 'all', COALESCE(ROUND(SUM(CASE WHEN percent_played IS NULL
            THEN LEAST(15, COALESCE(duration_secs, 0) * 0.25)
            ELSE COALESCE(duration_secs, 0) * percent_played / 100.0 END)), 0) FROM anon
    UNION ALL
    SELECT 'signups', 'all', 'all', COUNT(*) FROM users
    WHERE created_at >= :dstart AND created_at < :dend AND exclude_from_metrics = FALSE
    UNION ALL
    SELECT 'signups', 'role', role, COUNT(*) FROM users
    WHERE created_at >= :dstart AND created_at < :dend AND exclude_from_metrics = FALSE GROUP BY role
    UNION ALL
    SELECT 'gpv_cents', 'all', 'all', COALESCE(SUM(amount), 0) FROM pay
    UNION ALL
    SELECT 'platform_fee_cents', 'all', 'all', COALESCE(SUM(platform_fee), 0) FROM pay
    UNION ALL
    -- Ad income is only known per month, when the month is closed in Admin →
    -- Community Share (see period_metrics.sql). Ad views are no longer
    -- recorded. Both rows stay at 0 so daily charts keep their shape.
    SELECT 'ad_views', 'all', 'all', 0
    UNION ALL
    SELECT 'ad_unis_share_cents', 'all', 'all', 0
) rows
