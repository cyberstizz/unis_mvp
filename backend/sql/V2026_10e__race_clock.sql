-- =========================================================================
-- V2026_10e — Race clock: every jurisdiction keeps its own midnight
-- =========================================================================
-- Apply by hand in the Supabase SQL Editor (production), BEFORE deploying the
-- backend that reads jurisdictions.timezone. Safe to re-run: every statement
-- uses IF NOT EXISTS, OR REPLACE, or only fills empty values.
--
-- What it adds:
--   1. jurisdictions.timezone — the real-world clock each place runs on
--      (an IANA name such as America/New_York). Filled for the national root,
--      every state, and the few metros that sit in a different zone from
--      their state; every other jurisdiction inherits from its parent.
--      New jurisdictions inherit automatically (trigger), and a misspelled
--      zone name is refused instead of silently breaking a race.
--   2. race_period_closures — the ledger the hourly award job writes. One row
--      per jurisdiction + interval + period once that period is decided, so a
--      period is never decided twice and a missed run is caught up next hour.
--   3. ux_awards_one_winner_per_slot — the database itself refuses a second
--      winner for the same category, jurisdiction, genre, interval and date.
--   4. idx_likes_created_at — likes are now counted by the artist's local
--      day, which scans likes by time.
--
-- Rules this supports (see RaceClockService / RankingService):
--   - A vote counts on the voter's own calendar day.
--   - Plays and likes count on the artist's home calendar day.
--   - A race closes when its day has ended everywhere its voters and artists
--     live. A Harlem race closes at midnight New York time; the Unis-wide race
--     waits for the last zone anyone on Unis lives in.
-- =========================================================================


-- ── 1. jurisdictions.timezone ────────────────────────────────────────────

ALTER TABLE jurisdictions ADD COLUMN IF NOT EXISTS timezone VARCHAR(64);

-- 1a. The national root keeps Eastern as its own clock. (The Unis-wide race
--     does not close on this alone — it waits for every zone its people live in.)
UPDATE jurisdictions
SET timezone = 'America/New_York'
WHERE parent_jurisdiction_id IS NULL
  AND timezone IS NULL;

-- 1b. States: the zone most of the state's people live in.
UPDATE jurisdictions s
SET timezone = z.tz
FROM (VALUES
    ('alabama',              'America/Chicago'),
    ('alaska',               'America/Anchorage'),
    ('arizona',              'America/Phoenix'),
    ('arkansas',             'America/Chicago'),
    ('california',           'America/Los_Angeles'),
    ('colorado',             'America/Denver'),
    ('connecticut',          'America/New_York'),
    ('delaware',             'America/New_York'),
    ('district of columbia', 'America/New_York'),
    ('washington dc',        'America/New_York'),
    ('washington d.c.',      'America/New_York'),
    ('florida',              'America/New_York'),
    ('georgia',              'America/New_York'),
    ('hawaii',               'Pacific/Honolulu'),
    ('idaho',                'America/Boise'),
    ('illinois',             'America/Chicago'),
    ('indiana',              'America/Indiana/Indianapolis'),
    ('iowa',                 'America/Chicago'),
    ('kansas',               'America/Chicago'),
    ('kentucky',             'America/New_York'),
    ('louisiana',            'America/Chicago'),
    ('maine',                'America/New_York'),
    ('maryland',             'America/New_York'),
    ('massachusetts',        'America/New_York'),
    ('michigan',             'America/Detroit'),
    ('minnesota',            'America/Chicago'),
    ('mississippi',          'America/Chicago'),
    ('missouri',             'America/Chicago'),
    ('montana',              'America/Denver'),
    ('nebraska',             'America/Chicago'),
    ('nevada',               'America/Los_Angeles'),
    ('new hampshire',        'America/New_York'),
    ('new jersey',           'America/New_York'),
    ('new mexico',           'America/Denver'),
    ('new york',             'America/New_York'),
    ('north carolina',       'America/New_York'),
    ('north dakota',         'America/Chicago'),
    ('ohio',                 'America/New_York'),
    ('oklahoma',             'America/Chicago'),
    ('oregon',               'America/Los_Angeles'),
    ('pennsylvania',         'America/New_York'),
    ('puerto rico',          'America/Puerto_Rico'),
    ('rhode island',         'America/New_York'),
    ('south carolina',       'America/New_York'),
    ('south dakota',         'America/Chicago'),
    ('tennessee',            'America/Chicago'),
    ('texas',                'America/Chicago'),
    ('utah',                 'America/Denver'),
    ('vermont',              'America/New_York'),
    ('virginia',             'America/New_York'),
    ('washington',           'America/Los_Angeles'),
    ('west virginia',        'America/New_York'),
    ('wisconsin',            'America/Chicago'),
    ('wyoming',              'America/Denver')
) AS z(state_name, tz)
WHERE lower(trim(s.name)) = z.state_name
  AND s.parent_jurisdiction_id IN (
        SELECT jurisdiction_id FROM jurisdictions WHERE parent_jurisdiction_id IS NULL)
  AND s.timezone IS NULL;

-- 1c. Metros that run on a different clock from the rest of their state.
UPDATE jurisdictions m
SET timezone = z.tz
FROM (VALUES
    ('tennessee',    'knoxville',   'America/New_York'),
    ('tennessee',    'chattanooga', 'America/New_York'),
    ('texas',        'el paso',     'America/Denver'),
    ('south dakota', 'rapid city',  'America/Denver')
) AS z(state_name, metro_prefix, tz),
     jurisdictions st
WHERE m.parent_jurisdiction_id = st.jurisdiction_id
  AND lower(trim(st.name)) = z.state_name
  AND lower(trim(m.name)) LIKE z.metro_prefix || '%'
  AND m.timezone IS NULL;

-- 1d. Everything else inherits from its nearest ancestor that has a clock.
WITH RECURSIVE tree AS (
    SELECT jurisdiction_id, COALESCE(timezone, 'America/New_York') AS eff
    FROM jurisdictions
    WHERE parent_jurisdiction_id IS NULL
    UNION ALL
    SELECT j.jurisdiction_id, COALESCE(j.timezone, t.eff)
    FROM jurisdictions j
    JOIN tree t ON j.parent_jurisdiction_id = t.jurisdiction_id
)
UPDATE jurisdictions j
SET timezone = tree.eff
FROM tree
WHERE j.jurisdiction_id = tree.jurisdiction_id
  AND j.timezone IS NULL;

-- 1e. Anything not reachable from the root (should be none) gets Eastern.
UPDATE jurisdictions SET timezone = 'America/New_York' WHERE timezone IS NULL;

-- 1f. New or edited jurisdictions: inherit the parent's clock when none is
--     given, and refuse a zone name Postgres does not recognise.
CREATE OR REPLACE FUNCTION jurisdictions_timezone_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.timezone IS NULL AND NEW.parent_jurisdiction_id IS NOT NULL THEN
        SELECT timezone INTO NEW.timezone
        FROM jurisdictions
        WHERE jurisdiction_id = NEW.parent_jurisdiction_id;
    END IF;
    IF NEW.timezone IS NULL THEN
        NEW.timezone := 'America/New_York';
    END IF;
    -- Raises "time zone ... not recognized" for a bad name.
    PERFORM NOW() AT TIME ZONE NEW.timezone;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_jurisdictions_timezone ON jurisdictions;
CREATE TRIGGER trg_jurisdictions_timezone
    BEFORE INSERT OR UPDATE OF timezone, parent_jurisdiction_id ON jurisdictions
    FOR EACH ROW EXECUTE FUNCTION jurisdictions_timezone_guard();

ALTER TABLE jurisdictions ALTER COLUMN timezone SET NOT NULL;


-- ── 2. The award ledger ──────────────────────────────────────────────────
-- kind = CLOSED   the period was decided by the hourly job
-- kind = BASELINE the job first saw this race after the period had already
--                 ended (first deploy, or a jurisdiction just switched on).
--                 Nothing is decided for a baseline period, so switching a
--                 region on never hands out awards for days before it opened.

CREATE TABLE IF NOT EXISTS race_period_closures (
    jurisdiction_id UUID        NOT NULL REFERENCES jurisdictions (jurisdiction_id) ON DELETE CASCADE,
    interval_id     UUID        NOT NULL REFERENCES voting_intervals (interval_id) ON DELETE CASCADE,
    period_end      DATE        NOT NULL,
    kind            VARCHAR(10) NOT NULL DEFAULT 'CLOSED' CHECK (kind IN ('CLOSED', 'BASELINE')),
    awards_created  INT         NOT NULL DEFAULT 0,
    closed_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (jurisdiction_id, interval_id, period_end)
);


-- ── 3. One winner per slot ───────────────────────────────────────────────
-- If this fails with "could not create unique index", a slot already holds two
-- winners. Stop and send the error before going further.

CREATE UNIQUE INDEX IF NOT EXISTS ux_awards_one_winner_per_slot
    ON awards (target_type, jurisdiction_id, genre_id, interval_id, award_date);


-- ── 4. Likes by time ─────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_likes_created_at ON likes (created_at);


-- =========================================================================
-- CHECKS — run after applying. Each should match the note beside it.
-- =========================================================================
-- Every jurisdiction has a clock (expect 0):
--   SELECT COUNT(*) FROM jurisdictions WHERE timezone IS NULL;
--
-- The Harlem chain is on New York time (expect every row America/New_York):
--   SELECT name, timezone FROM jurisdictions
--   WHERE name IN ('Unis','New York','New York City','Manhattan','Harlem',
--                  'Uptown Harlem','Downtown Harlem');
--
-- How many jurisdictions sit on each clock:
--   SELECT timezone, COUNT(*) FROM jurisdictions GROUP BY timezone ORDER BY 2 DESC;
--
-- Ledger and index exist (expect one row each):
--   SELECT to_regclass('public.race_period_closures');
--   SELECT to_regclass('public.ux_awards_one_winner_per_slot');
