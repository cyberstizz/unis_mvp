-- Shared by the daily queries: who may count on day :d.
-- MetricsService pastes this in where a query says {{ELIGIBLE_USERS}}.
--   :d       the day (New York date)
--   :dstart  the day's start, in the database's clock
--   :dend    the next day's start, in the database's clock
SELECT u.user_id,
       u.role,
       u.jurisdiction_id                    AS area_id,
       unis_age_band(u.date_of_birth, :d)   AS age_band,
       unis_gender(u.gender)                AS gender
FROM users u
WHERE u.exclude_from_metrics = FALSE
  AND u.created_at < :dend
  AND (u.deleted_at IS NULL OR u.deleted_at >= :dend)
  AND NOT EXISTS (
        SELECT 1 FROM account_suspensions s
        WHERE s.user_id = u.user_id
          AND s.created_at < :dend
          AND (s.lifted_at  IS NULL OR s.lifted_at  > :dstart)
          AND (s.expires_at IS NULL OR s.expires_at > :dstart))
