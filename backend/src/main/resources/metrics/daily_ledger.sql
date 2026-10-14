-- Writes one user_active_days row per person active on day :d.
-- The caller deletes that day's rows first, so a rerun never double-counts.
WITH elig AS (
    {{ELIGIBLE_USERS}}
),
plays AS (
    -- Counted plays, without artists playing their own songs.
    SELECT sp.user_id,
           COUNT(*) AS plays,
           SUM(CASE WHEN sp.percent_played IS NULL
                    THEN LEAST(15, COALESCE(sp.duration_secs, 0) * 0.25)
                    ELSE COALESCE(sp.duration_secs, 0) * sp.percent_played / 100.0 END) AS listen_secs
    FROM song_plays sp
    JOIN songs s ON s.song_id = sp.song_id
    WHERE sp.played_at >= :dstart AND sp.played_at < :dend
      AND sp.user_id IS NOT NULL
      AND s.artist_id <> sp.user_id
    GROUP BY sp.user_id
),
platforms AS (
    SELECT x.user_id, string_agg(DISTINCT x.platform, ',' ORDER BY x.platform) AS platforms
    FROM (
        SELECT user_id, platform FROM song_plays
        WHERE played_at >= :dstart AND played_at < :dend AND platform IS NOT NULL
        UNION ALL
        SELECT user_id, platform FROM user_activity
        WHERE created_at >= :dstart AND created_at < :dend AND platform IS NOT NULL
    ) x
    GROUP BY x.user_id
),
voted AS (
    SELECT DISTINCT user_id FROM votes WHERE created_at >= :dstart AND created_at < :dend
),
social AS (
    SELECT user_id FROM likes    WHERE created_at >= :dstart AND created_at < :dend
    UNION
    SELECT user_id FROM comments WHERE created_at >= :dstart AND created_at < :dend
    UNION
    SELECT follower_id FROM follows WHERE created_at >= :dstart AND created_at < :dend
),
listed AS (
    SELECT created_by AS user_id FROM playlist WHERE created_at >= :dstart AND created_at < :dend
    UNION
    SELECT added_by_user_id FROM playlist_items WHERE added_at >= :dstart AND added_at < :dend
),
paid AS (
    SELECT buyer_id AS user_id FROM purchases
    WHERE created_at >= :dstart AND created_at < :dend AND status IN ('completed', 'refunded', 'disputed')
    UNION
    SELECT supporter_id FROM support_payments
    WHERE created_at >= :dstart AND created_at < :dend AND status IN ('completed', 'refunded', 'disputed')
),
messaged AS (
    SELECT DISTINCT sender_id AS user_id FROM messages WHERE created_at >= :dstart AND created_at < :dend
)
INSERT INTO user_active_days
    (activity_date, user_id, role, area_id, age_band, gender,
     played, voted, social, playlist, paid, messaged, plays, listen_secs, platforms)
SELECT CAST(:d AS DATE), e.user_id, e.role, e.area_id, e.age_band, e.gender,
       p.user_id  IS NOT NULL,
       v.user_id  IS NOT NULL,
       so.user_id IS NOT NULL,
       l.user_id  IS NOT NULL,
       pa.user_id IS NOT NULL,
       m.user_id  IS NOT NULL,
       COALESCE(p.plays, 0),
       COALESCE(ROUND(p.listen_secs), 0),
       pf.platforms
FROM elig e
LEFT JOIN plays     p  ON p.user_id  = e.user_id
LEFT JOIN voted     v  ON v.user_id  = e.user_id
LEFT JOIN social    so ON so.user_id = e.user_id
LEFT JOIN listed    l  ON l.user_id  = e.user_id
LEFT JOIN paid      pa ON pa.user_id = e.user_id
LEFT JOIN messaged  m  ON m.user_id  = e.user_id
LEFT JOIN platforms pf ON pf.user_id = e.user_id
WHERE p.user_id IS NOT NULL OR v.user_id IS NOT NULL OR so.user_id IS NOT NULL
   OR l.user_id IS NOT NULL OR pa.user_id IS NOT NULL OR m.user_id IS NOT NULL
