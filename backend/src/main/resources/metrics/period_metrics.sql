-- Every metric for one period: a calendar month for the monthly close, or a
-- custom date range for an unofficial report. Returns rows; the caller saves
-- them (monthly) or sends them straight back (custom range).
--   :first, :last          first and last day of the period (New York dates)
--   :mstart, :mend         the period's bounds in the database clock
--   :wstart                first day of the 30-day window ending on :last
--   :prev_first, :prev_last  the period before, for growth accounting
--   :dbtz                  the database clock's time zone, e.g. UTC
--   :suspicious            daily counted plays above which an account is flagged
WITH
month_rows AS (
    SELECT * FROM user_active_days WHERE activity_date BETWEEN :first AND :last
),
prev_rows AS (
    SELECT DISTINCT user_id FROM user_active_days WHERE activity_date BETWEEN :prev_first AND :prev_last
),
win AS (
    SELECT * FROM user_active_days WHERE activity_date BETWEEN :wstart AND :last
),
latest AS (
    SELECT DISTINCT ON (user_id) user_id, role, area_id, age_band, gender
    FROM win ORDER BY user_id, activity_date DESC
),
month_users AS (
    SELECT user_id, COUNT(*) AS days FROM month_rows GROUP BY user_id
),
-- Counted plays in the period by people eligible that day, without self-plays.
cp AS (
    SELECT sp.play_id, sp.user_id, sp.song_id, sp.completed, sp.source, sp.platform,
           sp.listener_jurisdiction_id, s.artist_id, s.jurisdiction_id AS song_area,
           s.genre_id, s.primary_subgenre_id, s.primary_mood_id, s.language,
           a.age_band,
           (sp.played_at AT TIME ZONE :dbtz AT TIME ZONE 'America/New_York') AS ny_time
    FROM song_plays sp
    JOIN songs s ON s.song_id = sp.song_id
    JOIN user_active_days a
      ON a.user_id = sp.user_id
     AND a.activity_date = CAST((sp.played_at AT TIME ZONE :dbtz AT TIME ZONE 'America/New_York') AS DATE)
    WHERE sp.played_at >= :mstart AND sp.played_at < :mend
      AND s.artist_id <> sp.user_id
),
cp_adult AS (
    SELECT * FROM cp WHERE age_band <> '13-17'
),
anon AS (
    SELECT * FROM anonymous_plays WHERE played_at >= :mstart AND played_at < :mend
),
-- Payments made in the period by accounts that count (any status).
-- card_fee is Stripe's card fee on the payment (2.9% rounded up + 30¢, the same
-- sum as PlatformFees.stripeCardFeeCents). Unis pays it out of its platform
-- fee, so what Unis actually keeps from a payment is platform_fee - card_fee.
pay_all AS (
    SELECT 'download' AS kind, p.buyer_id AS payer_id, p.artist_id, p.amount, p.platform_fee, p.status,
           (p.amount * 29 + 999) / 1000 + 30 AS card_fee
    FROM purchases p JOIN users u ON u.user_id = p.buyer_id
    WHERE p.created_at >= :mstart AND p.created_at < :mend AND u.exclude_from_metrics = FALSE
    UNION ALL
    SELECT 'support', s.supporter_id, s.artist_id, s.amount, s.platform_fee, s.status,
           (s.amount * 29 + 999) / 1000 + 30
    FROM support_payments s JOIN users u ON u.user_id = s.supporter_id
    WHERE s.created_at >= :mstart AND s.created_at < :mend AND u.exclude_from_metrics = FALSE
),
pay AS (
    SELECT * FROM pay_all WHERE status = 'completed'
),
-- Community Share: real income from months closed in Admin → Community Share
-- (replaces ad views priced at an estimated $3.50 per 1,000). A closed month
-- counts when its first day falls inside the period.
cs AS (
    SELECT COALESCE(SUM(counted_plays), 0) AS counted_plays,
           COALESCE(SUM(ad_income), 0) * 100 AS ad_cents,
           COALESCE(SUM(other_income), 0) * 100 AS other_cents,
           COALESCE(SUM(paid_to_community), 0) * 100 AS paid_cents
    FROM community_share_closes WHERE period_month BETWEEN :first AND :last
),
cs_streams AS (
    SELECT COALESCE(SUM(l.amount) FILTER (WHERE l.stream = 'supporter'), 0) * 100 AS artist_cents,
           COALESCE(SUM(l.amount) FILTER (WHERE l.stream LIKE 'referral%'), 0) * 100 AS referrer_cents
    FROM earnings_ledger l
    JOIN community_share_closes c ON c.close_id = l.close_id
    WHERE c.period_month BETWEEN :first AND :last
),
payer_info AS (
    SELECT p.*, u.jurisdiction_id AS payer_area, unis_age_band(u.date_of_birth, :last) AS payer_band,
           unis_gender(u.gender) AS payer_gender
    FROM pay p JOIN users u ON u.user_id = p.payer_id
),
new_users AS (
    SELECT u.user_id, u.role, u.created_at,
           CAST((u.created_at AT TIME ZONE :dbtz AT TIME ZONE 'America/New_York') AS DATE) AS created_day,
           CASE WHEN u.signup_source IS NOT NULL THEN u.signup_source
                WHEN EXISTS (SELECT 1 FROM referrals r WHERE r.referred_id = u.user_id) THEN 'referral'
                ELSE 'unknown' END AS source
    FROM users u
    WHERE u.created_at >= :mstart AND u.created_at < :mend AND u.exclude_from_metrics = FALSE
),
catalog AS (
    SELECT s.* FROM songs s JOIN users a ON a.user_id = s.artist_id
    WHERE s.created_at < :mend
      AND (s.deleted_at IS NULL OR s.deleted_at >= :mend)
      AND s.file_url IS NOT NULL
      AND a.exclude_from_metrics = FALSE
      AND s.song_id NOT IN (SELECT clean_version_id FROM songs WHERE clean_version_id IS NOT NULL)
),
artist_plays AS (
    SELECT artist_id, COUNT(*) AS plays, ROW_NUMBER() OVER (ORDER BY COUNT(*) DESC, artist_id) AS rnk
    FROM cp GROUP BY artist_id
),
-- Each adult play counted once for the listener's neighborhood and once for
-- each area above it (not the Unis root), for local charts.
cp_areas AS (
    SELECT c.*, anc.ancestor_id AS area
    FROM cp_adult c
    JOIN metric_area_ancestors anc ON anc.jurisdiction_id = c.listener_jurisdiction_id AND anc.ancestor_depth >= 1
),
area_song AS (
    SELECT area, song_id, COUNT(*) AS plays, COUNT(DISTINCT user_id) AS listeners,
           ROW_NUMBER() OVER (PARTITION BY area ORDER BY COUNT(*) DESC, song_id) AS rnk
    FROM cp_areas GROUP BY area, song_id
),
area_artist AS (
    SELECT area, artist_id, COUNT(*) AS plays, COUNT(DISTINCT user_id) AS listeners,
           ROW_NUMBER() OVER (PARTITION BY area ORDER BY COUNT(*) DESC, artist_id) AS rnk
    FROM cp_areas GROUP BY area, artist_id
),
area_sub AS (
    SELECT c.area, sg.slug, COUNT(*) AS plays, COUNT(DISTINCT c.user_id) AS listeners,
           ROW_NUMBER() OVER (PARTITION BY c.area ORDER BY COUNT(*) DESC, sg.slug) AS rnk
    FROM cp_areas c JOIN subgenres sg ON sg.subgenre_id = c.primary_subgenre_id
    GROUP BY c.area, sg.slug
),
prev_artists AS (
    -- Artists who joined in the period before; activated = 10+ different
    -- listeners within 30 days of joining.
    SELECT a.user_id,
           (SELECT COUNT(DISTINCT sp.user_id) FROM song_plays sp JOIN songs s ON s.song_id = sp.song_id
            JOIN users l ON l.user_id = sp.user_id
            WHERE s.artist_id = a.user_id AND sp.user_id <> a.user_id AND l.exclude_from_metrics = FALSE
              AND sp.played_at >= a.created_at AND sp.played_at < a.created_at + INTERVAL '30 days') AS listeners
    FROM users a
    WHERE a.role = 'artist' AND a.exclude_from_metrics = FALSE
      AND CAST((a.created_at AT TIME ZONE :dbtz AT TIME ZONE 'America/New_York') AS DATE) BETWEEN :prev_first AND :prev_last
),
fees AS (SELECT COALESCE(SUM(platform_fee), 0) AS cents FROM pay),
-- Stripe's card fees on those payments, and what Unis kept after paying them.
card_fees AS (SELECT COALESCE(SUM(card_fee), 0) AS cents FROM pay),
fee_margin AS (SELECT COALESCE(SUM(platform_fee - card_fee), 0) AS cents FROM pay),
-- What Unis kept of its ad and other income after paying the Community Share
-- (the share is funded by all income, fees included, so this can dip below
-- zero in a month with almost no ad income; net revenue below still adds up).
ad_unis AS (SELECT ad_cents + other_cents - paid_cents AS cents FROM cs),
-- Fees (after card fees) follow the payer's area. Community Share income
-- isn't tied to one area, so it's reported under "none".
area_rev AS (
    SELECT area, SUM(cents) AS cents FROM (
        SELECT payer_area AS area, platform_fee - card_fee AS cents FROM payer_info WHERE payer_band <> '13-17'
        UNION ALL
        SELECT CAST(NULL AS uuid), cents FROM ad_unis
    ) x GROUP BY area
)
SELECT k AS metric_key, st AS segment_type, sv AS segment_value, v AS value FROM (
    -- ── Users ──
    SELECT 'mau' AS k, 'all' AS st, 'all' AS sv, COUNT(*)::numeric AS v FROM latest
    UNION ALL SELECT 'mau', 'role', COALESCE(role, 'unknown'), COUNT(*) FROM latest GROUP BY role
    UNION ALL SELECT 'mau', 'age_band', age_band, COUNT(*) FROM latest GROUP BY age_band
    UNION ALL SELECT 'mau', 'gender', gender, COUNT(*) FROM latest WHERE age_band <> '13-17' GROUP BY gender
    UNION ALL SELECT 'mau', 'area', COALESCE(area_id::text, 'none'), COUNT(*) FROM latest WHERE age_band <> '13-17' GROUP BY area_id
    UNION ALL SELECT 'mau_adult_or_unknown', 'all', 'all', COUNT(*) FROM latest WHERE age_band <> '13-17'
    UNION ALL SELECT 'mal', 'all', 'all', COUNT(DISTINCT user_id) FROM win WHERE played
    UNION ALL SELECT 'voters_30d', 'all', 'all', COUNT(DISTINCT user_id) FROM win WHERE voted
    UNION ALL SELECT 'dau_avg', 'all', 'all', COALESCE(AVG(value), 0) FROM metric_daily
              WHERE metric_key = 'dau' AND segment_type = 'all' AND metric_date BETWEEN :first AND :last
    UNION ALL SELECT 'dau_avg', 'platform', segment_value, SUM(value) / GREATEST(1, (CAST(:last AS DATE) - CAST(:first AS DATE)) + 1)
              FROM metric_daily WHERE metric_key = 'dau' AND segment_type = 'platform'
              AND metric_date BETWEEN :first AND :last GROUP BY segment_value
    UNION ALL SELECT 'mau_avg', 'all', 'all', COALESCE(AVG(value), 0) FROM metric_daily
              WHERE metric_key = 'mau' AND segment_type = 'all' AND metric_date BETWEEN :first AND :last
    UNION ALL SELECT 'mau_avg', 'area', segment_value, SUM(value) / GREATEST(1, (CAST(:last AS DATE) - CAST(:first AS DATE)) + 1)
              FROM metric_daily WHERE metric_key = 'mau' AND segment_type = 'area'
              AND metric_date BETWEEN :first AND :last GROUP BY segment_value
    UNION ALL SELECT 'active_users_period', 'all', 'all', COUNT(*) FROM month_users
    UNION ALL SELECT 'active_days_total', 'all', 'all', COALESCE(SUM(days), 0) FROM month_users

    -- ── Growth ──
    UNION ALL SELECT 'signups', 'all', 'all', COUNT(*) FROM new_users
    UNION ALL SELECT 'signups', 'role', role, COUNT(*) FROM new_users GROUP BY role
    UNION ALL SELECT 'signups', 'source', source, COUNT(*) FROM new_users GROUP BY source
    UNION ALL SELECT 'activated_signups', 'all', 'all', COUNT(*) FROM new_users n
              WHERE EXISTS (SELECT 1 FROM user_active_days a WHERE a.user_id = n.user_id
                            AND (a.played OR a.voted)
                            AND a.activity_date BETWEEN n.created_day AND n.created_day + 6)
    UNION ALL SELECT 'ga_new', 'all', 'all', COUNT(*) FROM month_users m
              WHERE EXISTS (SELECT 1 FROM new_users n WHERE n.user_id = m.user_id)
    UNION ALL SELECT 'ga_retained', 'all', 'all', COUNT(*) FROM month_users m
              WHERE EXISTS (SELECT 1 FROM prev_rows p WHERE p.user_id = m.user_id)
                AND NOT EXISTS (SELECT 1 FROM new_users n WHERE n.user_id = m.user_id)
    UNION ALL SELECT 'ga_returning', 'all', 'all', COUNT(*) FROM month_users m
              WHERE NOT EXISTS (SELECT 1 FROM prev_rows p WHERE p.user_id = m.user_id)
                AND NOT EXISTS (SELECT 1 FROM new_users n WHERE n.user_id = m.user_id)
    UNION ALL SELECT 'ga_lost', 'all', 'all', COUNT(*) FROM prev_rows p
              WHERE NOT EXISTS (SELECT 1 FROM month_users m WHERE m.user_id = p.user_id)
    UNION ALL SELECT 'account_closures', 'all', 'all', COUNT(*) FROM users
              WHERE deleted_at >= :mstart AND deleted_at < :mend AND exclude_from_metrics = FALSE
    UNION ALL SELECT 'waitlist_signups', 'all', 'all', COUNT(*) FROM pre_registrations
              WHERE created_at >= :mstart AND created_at < :mend
    UNION ALL SELECT 'waitlist_signups', 'metro', metro, n FROM (
                SELECT COALESCE(state_code, '?') || ' | ' || COALESCE(metro_region, 'Other') AS metro, COUNT(*) AS n
                FROM pre_registrations WHERE created_at >= :mstart AND created_at < :mend
                GROUP BY 1 ORDER BY 2 DESC LIMIT 25) w
    UNION ALL SELECT 'waitlist_converted', 'all', 'all', COUNT(*) FROM new_users WHERE source = 'waitlist'

    -- ── Engagement ──
    UNION ALL SELECT 'plays', 'all', 'all', COUNT(*) FROM cp
    UNION ALL SELECT 'listen_secs', 'all', 'all', COALESCE(SUM(listen_secs), 0) FROM month_rows
    UNION ALL SELECT 'listeners_period', 'all', 'all', COUNT(DISTINCT user_id) FROM month_rows WHERE played
    UNION ALL SELECT 'completed_plays', 'all', 'all', COUNT(*) FROM cp WHERE completed
    UNION ALL SELECT 'plays', 'source', COALESCE(NULLIF(source, ''), 'other'), COUNT(*) FROM cp GROUP BY 3
    UNION ALL SELECT 'plays', 'platform', COALESCE(platform, 'unknown'), COUNT(*) FROM cp GROUP BY 3
    UNION ALL SELECT 'plays', 'hour', LPAD(EXTRACT(HOUR FROM ny_time)::int::text, 2, '0'), COUNT(*) FROM cp GROUP BY 3
    UNION ALL SELECT 'plays', 'weekday', EXTRACT(ISODOW FROM ny_time)::int::text, COUNT(*) FROM cp GROUP BY 3
    UNION ALL SELECT 'local_plays', 'all', 'all', COUNT(*) FROM cp
              JOIN metric_local_area la ON la.jurisdiction_id = cp.listener_jurisdiction_id
              JOIN metric_local_area ls ON ls.jurisdiction_id = cp.song_area
              WHERE la.local_area_id = ls.local_area_id
    UNION ALL SELECT 'votes', 'all', 'all', COUNT(*) FROM votes v
              JOIN user_active_days a ON a.user_id = v.user_id AND a.voted
               AND a.activity_date = CAST((v.created_at AT TIME ZONE :dbtz AT TIME ZONE 'America/New_York') AS DATE)
              WHERE v.created_at >= :mstart AND v.created_at < :mend
    UNION ALL SELECT 'votes', 'area', COALESCE(v.jurisdiction_id::text, 'none'), COUNT(*) FROM votes v
              JOIN user_active_days a ON a.user_id = v.user_id AND a.voted
               AND a.activity_date = CAST((v.created_at AT TIME ZONE :dbtz AT TIME ZONE 'America/New_York') AS DATE)
              WHERE v.created_at >= :mstart AND v.created_at < :mend GROUP BY v.jurisdiction_id
    UNION ALL SELECT 'votes', 'genre', COALESCE(g.name, 'none'), COUNT(*) FROM votes v
              JOIN user_active_days a ON a.user_id = v.user_id AND a.voted
               AND a.activity_date = CAST((v.created_at AT TIME ZONE :dbtz AT TIME ZONE 'America/New_York') AS DATE)
              LEFT JOIN genres g ON g.genre_id = v.genre_id
              WHERE v.created_at >= :mstart AND v.created_at < :mend GROUP BY g.name
    UNION ALL SELECT 'voters_period', 'all', 'all', COUNT(DISTINCT user_id) FROM month_rows WHERE voted
    UNION ALL SELECT 'likes', 'all', 'all', COUNT(*) FROM likes x JOIN month_rows a ON a.user_id = x.user_id AND a.social
              AND a.activity_date = CAST((x.created_at AT TIME ZONE :dbtz AT TIME ZONE 'America/New_York') AS DATE)
              WHERE x.created_at >= :mstart AND x.created_at < :mend
    UNION ALL SELECT 'comments', 'all', 'all', COUNT(*) FROM comments x JOIN month_rows a ON a.user_id = x.user_id AND a.social
              AND a.activity_date = CAST((x.created_at AT TIME ZONE :dbtz AT TIME ZONE 'America/New_York') AS DATE)
              WHERE x.created_at >= :mstart AND x.created_at < :mend
    UNION ALL SELECT 'follows', 'all', 'all', COUNT(*) FROM follows x JOIN month_rows a ON a.user_id = x.follower_id AND a.social
              AND a.activity_date = CAST((x.created_at AT TIME ZONE :dbtz AT TIME ZONE 'America/New_York') AS DATE)
              WHERE x.created_at >= :mstart AND x.created_at < :mend
    UNION ALL SELECT 'messages', 'all', 'all', COUNT(*) FROM messages x JOIN month_rows a ON a.user_id = x.sender_id AND a.messaged
              AND a.activity_date = CAST((x.created_at AT TIME ZONE :dbtz AT TIME ZONE 'America/New_York') AS DATE)
              WHERE x.created_at >= :mstart AND x.created_at < :mend
    UNION ALL SELECT 'playlists_created', 'all', 'all', COUNT(*) FROM playlist x JOIN month_rows a ON a.user_id = x.created_by AND a.playlist
              AND a.activity_date = CAST((x.created_at AT TIME ZONE :dbtz AT TIME ZONE 'America/New_York') AS DATE)
              WHERE x.created_at >= :mstart AND x.created_at < :mend
    UNION ALL SELECT 'playlist_adds', 'all', 'all', COUNT(*) FROM playlist_items x JOIN month_rows a ON a.user_id = x.added_by_user_id AND a.playlist
              AND a.activity_date = CAST((x.added_at AT TIME ZONE :dbtz AT TIME ZONE 'America/New_York') AS DATE)
              WHERE x.added_at >= :mstart AND x.added_at < :mend
    UNION ALL SELECT 'anon_plays', 'all', 'all', COUNT(*) FROM anon
    UNION ALL SELECT 'anon_plays', 'platform', COALESCE(platform, 'unknown'), COUNT(*) FROM anon GROUP BY 3
    UNION ALL SELECT 'anon_visitors', 'all', 'all', COUNT(DISTINCT visitor_id) FROM anon
    UNION ALL SELECT 'anon_listen_secs', 'all', 'all', COALESCE(ROUND(SUM(CASE WHEN percent_played IS NULL
                THEN LEAST(15, COALESCE(duration_secs, 0) * 0.25)
                ELSE COALESCE(duration_secs, 0) * percent_played / 100.0 END)), 0) FROM anon

    -- ── Catalog ──
    UNION ALL SELECT 'catalog_songs', 'all', 'all', COUNT(*) FROM catalog
    UNION ALL SELECT 'tagged_songs', 'all', 'all', COUNT(*) FROM catalog
              WHERE (primary_subgenre_id IS NOT NULL OR subgenre_write_in IS NOT NULL) AND primary_mood_id IS NOT NULL
    UNION ALL SELECT 'uploads', 'all', 'all', COUNT(*) FROM catalog WHERE created_at >= :mstart
    UNION ALL SELECT 'songs_played', 'all', 'all', COUNT(DISTINCT cp.song_id) FROM cp JOIN catalog c ON c.song_id = cp.song_id
    UNION ALL SELECT 'active_artists', 'all', 'all', COUNT(*) FROM artist_plays
    UNION ALL SELECT 'artist_concentration', 'all', 'all',
              CASE WHEN COALESCE(SUM(plays), 0) = 0 THEN 0
                   ELSE 100.0 * SUM(plays) FILTER (WHERE rnk <= CEIL(0.1 * (SELECT COUNT(*) FROM artist_plays))) / SUM(plays) END
              FROM artist_plays
    UNION ALL SELECT 'plays', 'genre', COALESCE(g.name, 'none'), COUNT(*) FROM cp LEFT JOIN genres g ON g.genre_id = cp.genre_id GROUP BY g.name
    UNION ALL SELECT 'plays', 'subgenre', COALESCE(sg.slug, 'untagged'), COUNT(*) FROM cp
              LEFT JOIN subgenres sg ON sg.subgenre_id = cp.primary_subgenre_id GROUP BY sg.slug
    UNION ALL SELECT 'plays', 'mood', COALESCE(md.slug, 'untagged'), COUNT(*) FROM cp
              LEFT JOIN moods md ON md.mood_id = cp.primary_mood_id GROUP BY md.slug
    UNION ALL SELECT 'new_artists', 'all', 'all', COUNT(*) FROM new_users WHERE role = 'artist'
    UNION ALL SELECT 'prev_new_artists', 'all', 'all', COUNT(*) FROM prev_artists
    UNION ALL SELECT 'prev_new_artists_activated', 'all', 'all', COUNT(*) FROM prev_artists WHERE listeners >= 10

    -- ── Money ──
    UNION ALL SELECT 'gpv_cents', 'all', 'all', COALESCE(SUM(amount), 0) FROM pay
    UNION ALL SELECT 'gpv_cents', 'kind', kind, COALESCE(SUM(amount), 0) FROM pay GROUP BY kind
    UNION ALL SELECT 'platform_fee_cents', 'all', 'all', cents FROM fees
    UNION ALL SELECT 'card_fees_cents', 'all', 'all', cents FROM card_fees
    UNION ALL SELECT 'payments', 'all', 'all', COUNT(*) FROM pay
    UNION ALL SELECT 'payments_made', 'all', 'all', COUNT(*) FROM pay_all
    UNION ALL SELECT 'payments_made_cents', 'all', 'all', COALESCE(SUM(amount), 0) FROM pay_all
    UNION ALL SELECT 'refunded_payments', 'all', 'all', COUNT(*) FROM pay_all WHERE status IN ('refunded', 'disputed')
    UNION ALL SELECT 'refunded_cents', 'all', 'all', COALESCE(SUM(amount), 0) FROM pay_all WHERE status IN ('refunded', 'disputed')
    UNION ALL SELECT 'paying_users', 'all', 'all', COUNT(DISTINCT payer_id) FROM pay
    -- Community Share (key names kept from the ad-view era): counted plays
    -- behind the share, ad income received, Unis's keep, and what went to
    -- supported artists and referrers.
    UNION ALL SELECT 'ad_views', 'all', 'all', counted_plays FROM cs
    UNION ALL SELECT 'ad_revenue_cents', 'all', 'all', ad_cents FROM cs
    UNION ALL SELECT 'ad_unis_share_cents', 'all', 'all', cents FROM ad_unis
    UNION ALL SELECT 'ad_artist_share_cents', 'all', 'all', artist_cents FROM cs_streams
    UNION ALL SELECT 'ad_referrer_share_cents', 'all', 'all', referrer_cents FROM cs_streams
    UNION ALL SELECT 'net_revenue_cents', 'all', 'all', (SELECT cents FROM fee_margin) + (SELECT cents FROM ad_unis)
    UNION ALL SELECT 'net_revenue_cents', 'area', COALESCE(area::text, 'none'), cents FROM area_rev
    UNION ALL SELECT 'artist_earnings_cents', 'all', 'all',
              COALESCE((SELECT SUM(amount - COALESCE(platform_fee, 0)) FROM pay), 0)
              + (SELECT artist_cents FROM cs_streams)
    UNION ALL SELECT 'payouts_sent', 'all', 'all', COUNT(*) FROM payouts
              WHERE status = 'completed' AND completed_at >= :mstart AND completed_at < :mend
    UNION ALL SELECT 'payouts_sent_cents', 'all', 'all', COALESCE(SUM(amount) * 100, 0) FROM payouts
              WHERE status = 'completed' AND completed_at >= :mstart AND completed_at < :mend
    UNION ALL SELECT 'payouts_failed', 'all', 'all', COUNT(*) FROM payouts
              WHERE status = 'failed' AND created_at >= :mstart AND created_at < :mend

    -- ── Audience (people 18 and over, or with no birth date) ──
    UNION ALL SELECT 'taste_plays', 'age_subgenre', c.age_band || '|' || sg.slug, COUNT(*) FROM cp_adult c
              JOIN subgenres sg ON sg.subgenre_id = c.primary_subgenre_id GROUP BY c.age_band, sg.slug
    UNION ALL SELECT 'taste_listeners', 'age_subgenre', c.age_band || '|' || sg.slug, COUNT(DISTINCT c.user_id) FROM cp_adult c
              JOIN subgenres sg ON sg.subgenre_id = c.primary_subgenre_id GROUP BY c.age_band, sg.slug
    UNION ALL SELECT 'taste_plays', 'age_mood', c.age_band || '|' || md.slug, COUNT(*) FROM cp_adult c
              JOIN moods md ON md.mood_id = c.primary_mood_id GROUP BY c.age_band, md.slug
    UNION ALL SELECT 'taste_listeners', 'age_mood', c.age_band || '|' || md.slug, COUNT(DISTINCT c.user_id) FROM cp_adult c
              JOIN moods md ON md.mood_id = c.primary_mood_id GROUP BY c.age_band, md.slug
    UNION ALL SELECT 'hour_plays', 'age_hour', age_band || '|' || LPAD(EXTRACT(HOUR FROM ny_time)::int::text, 2, '0'), COUNT(*)
              FROM cp_adult GROUP BY 3
    UNION ALL SELECT 'hour_listeners', 'age_band', age_band, COUNT(DISTINCT user_id) FROM cp_adult GROUP BY age_band
    UNION ALL SELECT 'chart_song_plays', 'area_song', area::text || '|' || song_id::text, plays FROM area_song WHERE rnk <= 10
    UNION ALL SELECT 'chart_song_listeners', 'area_song', area::text || '|' || song_id::text, listeners FROM area_song WHERE rnk <= 10
    UNION ALL SELECT 'chart_artist_plays', 'area_artist', area::text || '|' || artist_id::text, plays FROM area_artist WHERE rnk <= 10
    UNION ALL SELECT 'chart_artist_listeners', 'area_artist', area::text || '|' || artist_id::text, listeners FROM area_artist WHERE rnk <= 10
    UNION ALL SELECT 'chart_subgenre_plays', 'area_subgenre', area::text || '|' || slug, plays FROM area_sub WHERE rnk <= 10
    UNION ALL SELECT 'chart_subgenre_listeners', 'area_subgenre', area::text || '|' || slug, listeners FROM area_sub WHERE rnk <= 10
    UNION ALL SELECT 'plays', 'language', COALESCE(language, 'not_set'), COUNT(*) FROM cp GROUP BY 3
    UNION ALL SELECT 'paying_users', 'age_band', payer_band, COUNT(DISTINCT payer_id) FROM payer_info
              WHERE payer_band <> '13-17' GROUP BY payer_band
    UNION ALL SELECT 'paying_users', 'gender', payer_gender, COUNT(DISTINCT payer_id) FROM payer_info
              WHERE payer_band <> '13-17' GROUP BY payer_gender
    UNION ALL SELECT 'paying_users', 'area', COALESCE(payer_area::text, 'none'), COUNT(DISTINCT payer_id) FROM payer_info
              WHERE payer_band <> '13-17' GROUP BY payer_area

    -- ── Integrity ──
    UNION ALL SELECT 'excluded_flagged', 'all', 'all', COUNT(*) FROM users
              WHERE exclude_from_metrics = TRUE AND created_at < :mend AND (deleted_at IS NULL OR deleted_at >= :mend)
    UNION ALL SELECT 'suspended_active', 'all', 'all', COUNT(DISTINCT user_id) FROM account_suspensions
              WHERE created_at < :mend AND (lifted_at IS NULL OR lifted_at >= :mend) AND (expires_at IS NULL OR expires_at >= :mend)
    UNION ALL SELECT 'closed_accounts', 'all', 'all', COUNT(*) FROM users WHERE deleted_at < :mend
    UNION ALL SELECT 'suspicious_plays', 'all', 'all', COALESCE(SUM(plays), 0) FROM month_rows WHERE plays > :suspicious
    UNION ALL SELECT 'dmca_claims', 'all', 'all', COUNT(*) FROM dmca_claims WHERE created_at >= :mstart AND created_at < :mend
) rows
