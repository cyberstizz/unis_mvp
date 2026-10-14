-- =========================================================================
-- V2026_10d — Metrics and investor reporting
-- =========================================================================
-- Apply by hand in the Supabase SQL Editor (production). Safe to re-run:
-- every statement uses IF NOT EXISTS or ON CONFLICT.
--
-- What it adds:
--   1. Capture fields: platform on plays and activity, how each user signed
--      up, and a flag that keeps staff and test accounts out of the numbers.
--   2. anonymous_plays: listening by people who aren't logged in. Kept apart
--      from song_plays on purpose, so it never touches points, scores,
--      awards or anyone's play count.
--   3. The snapshot tables the nightly job writes and the dashboard and PDFs
--      read: metric_definitions, user_active_days, metric_daily,
--      metric_monthly, metric_closes, cohort_retention.
--   4. The definitions themselves (version 1), printed in every report.
-- =========================================================================


-- ── 1. Capture fields ────────────────────────────────────────────────────

ALTER TABLE users ADD COLUMN IF NOT EXISTS exclude_from_metrics BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS signup_source   VARCHAR(20);   -- referral | waitlist | campaign | organic
ALTER TABLE users ADD COLUMN IF NOT EXISTS signup_campaign VARCHAR(80);   -- utm_campaign or ?c= code, when one was used
ALTER TABLE users ADD COLUMN IF NOT EXISTS signup_platform VARCHAR(10);   -- web | ios | android

ALTER TABLE song_plays    ADD COLUMN IF NOT EXISTS platform VARCHAR(10);
ALTER TABLE user_activity ADD COLUMN IF NOT EXISTS platform VARCHAR(10);

CREATE INDEX IF NOT EXISTS idx_song_plays_played_at    ON song_plays (played_at);
CREATE INDEX IF NOT EXISTS idx_user_activity_created_at ON user_activity (created_at);
CREATE INDEX IF NOT EXISTS idx_users_created_at        ON users (created_at);


-- ── 2. Logged-out listening ──────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS anonymous_plays (
    play_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visitor_id     UUID NOT NULL,                 -- random ID kept in the browser or app, not tied to a person
    song_id        UUID NOT NULL REFERENCES songs (song_id) ON DELETE CASCADE,
    played_at      TIMESTAMP NOT NULL DEFAULT now(),
    duration_secs  INTEGER,                       -- song length at play time
    percent_played NUMERIC(5,2),                  -- set by the "play finished" message
    completed      BOOLEAN NOT NULL DEFAULT FALSE,
    source         VARCHAR(40),
    platform       VARCHAR(10)
);
CREATE INDEX IF NOT EXISTS idx_anonymous_plays_played_at ON anonymous_plays (played_at);
CREATE INDEX IF NOT EXISTS idx_anonymous_plays_visitor   ON anonymous_plays (visitor_id, played_at);


-- ── 3. Snapshot tables ───────────────────────────────────────────────────

-- One written definition per metric. Printed on every report's definitions page.
CREATE TABLE IF NOT EXISTS metric_definitions (
    metric_key     VARCHAR(60) PRIMARY KEY,
    category       VARCHAR(30)  NOT NULL,         -- headline | growth | engagement | catalog | economy | audience | cohorts | integrity
    name           VARCHAR(100) NOT NULL,
    definition     TEXT         NOT NULL,
    unit           VARCHAR(20)  NOT NULL,         -- count | percent | hours | usd | ratio | days
    is_headline    BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order     INTEGER      NOT NULL DEFAULT 0,
    version        INTEGER      NOT NULL DEFAULT 1,
    effective_from DATE         NOT NULL DEFAULT DATE '2026-11-21',
    change_note    TEXT
);

-- One row per person per day they were active. The base for every rolling
-- 30-day count and every cohort, and it survives clean-ups of raw activity.
CREATE TABLE IF NOT EXISTS user_active_days (
    activity_date DATE    NOT NULL,
    user_id       UUID    NOT NULL,
    role          VARCHAR(20),
    area_id       UUID,                           -- the person's neighborhood
    age_band      VARCHAR(12),                    -- on that day: 13-17, 18-24 ... 65+, not_given
    gender        VARCHAR(20),                    -- female | male | non-binary | not_stated
    played        BOOLEAN NOT NULL DEFAULT FALSE,
    voted         BOOLEAN NOT NULL DEFAULT FALSE,
    social        BOOLEAN NOT NULL DEFAULT FALSE, -- liked, commented or followed
    playlist      BOOLEAN NOT NULL DEFAULT FALSE, -- created a playlist or added a song to one
    paid          BOOLEAN NOT NULL DEFAULT FALSE, -- bought a download or paid support
    messaged      BOOLEAN NOT NULL DEFAULT FALSE, -- sent a direct message
    plays         INTEGER NOT NULL DEFAULT 0,
    listen_secs   INTEGER NOT NULL DEFAULT 0,
    platforms     VARCHAR(30),                    -- e.g. "android,web"
    PRIMARY KEY (activity_date, user_id)
);
CREATE INDEX IF NOT EXISTS idx_user_active_days_user ON user_active_days (user_id, activity_date);

-- One value per metric per day, overall ('all','all') and by segment.
CREATE TABLE IF NOT EXISTS metric_daily (
    metric_date   DATE          NOT NULL,
    metric_key    VARCHAR(60)   NOT NULL,
    segment_type  VARCHAR(30)   NOT NULL DEFAULT 'all',
    segment_value VARCHAR(120)  NOT NULL DEFAULT 'all',
    value         NUMERIC(20,4) NOT NULL,
    computed_at   TIMESTAMP     NOT NULL DEFAULT now(),
    PRIMARY KEY (metric_date, metric_key, segment_type, segment_value)
);

-- The same per month. "month" is the first day of the month.
CREATE TABLE IF NOT EXISTS metric_monthly (
    month         DATE          NOT NULL,
    metric_key    VARCHAR(60)   NOT NULL,
    segment_type  VARCHAR(30)   NOT NULL DEFAULT 'all',
    segment_value VARCHAR(120)  NOT NULL DEFAULT 'all',
    value         NUMERIC(20,4) NOT NULL,
    computed_at   TIMESTAMP     NOT NULL DEFAULT now(),
    PRIMARY KEY (month, metric_key, segment_type, segment_value)
);

-- A row here means the month is locked. Reruns need a written reason.
CREATE TABLE IF NOT EXISTS metric_closes (
    month               DATE PRIMARY KEY,
    closed_at           TIMESTAMP NOT NULL,
    definitions_version INTEGER   NOT NULL,
    official            BOOLEAN   NOT NULL,       -- false = before launch day (preview only)
    rerun_count         INTEGER   NOT NULL DEFAULT 0,
    last_rerun_at       TIMESTAMP,
    last_rerun_reason   TEXT,
    last_rerun_by       UUID
);

-- For each signup month and each month after it.
CREATE TABLE IF NOT EXISTS cohort_retention (
    cohort_month      DATE          NOT NULL,
    months_since      INTEGER       NOT NULL,
    segment_type      VARCHAR(30)   NOT NULL DEFAULT 'all',
    segment_value     VARCHAR(80)   NOT NULL DEFAULT 'all',
    cohort_size       INTEGER       NOT NULL,
    active_users      INTEGER       NOT NULL,
    listen_hours      NUMERIC(14,2) NOT NULL DEFAULT 0,
    gpv_cents         BIGINT        NOT NULL DEFAULT 0,   -- added up from signup to the end of this month
    net_revenue_cents BIGINT        NOT NULL DEFAULT 0,   -- added up from signup to the end of this month
    computed_at       TIMESTAMP     NOT NULL DEFAULT now(),
    PRIMARY KEY (cohort_month, months_since, segment_type, segment_value)
);


-- ── Helpers the metric queries share ─────────────────────────────────────

-- Age band on a given date. Bands from 18 up match the ones ad platforms sell.
CREATE OR REPLACE FUNCTION unis_age_band(dob DATE, ref DATE) RETURNS VARCHAR
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN dob IS NULL THEN 'not_given'
        WHEN date_part('year', age(ref, dob)) < 18 THEN '13-17'
        WHEN date_part('year', age(ref, dob)) < 25 THEN '18-24'
        WHEN date_part('year', age(ref, dob)) < 35 THEN '25-34'
        WHEN date_part('year', age(ref, dob)) < 45 THEN '35-44'
        WHEN date_part('year', age(ref, dob)) < 55 THEN '45-54'
        WHEN date_part('year', age(ref, dob)) < 65 THEN '55-64'
        ELSE '65+'
    END
$$;

-- Gender exactly as chosen at signup; anything else is "not stated".
CREATE OR REPLACE FUNCTION unis_gender(g VARCHAR) RETURNS VARCHAR
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN lower(g) IN ('female', 'male', 'non-binary') THEN lower(g) ELSE 'not_stated' END
$$;

-- Every jurisdiction with each of its ancestors (itself included) and the
-- ancestor's distance from the root. Unis is depth 0, states depth 1, so a
-- shared ancestor at depth 2 or deeper means "the same area below the state":
-- Uptown and Downtown both sit inside Harlem.
CREATE OR REPLACE VIEW metric_area_ancestors AS
WITH RECURSIVE depth_of AS (
    SELECT jurisdiction_id, 0 AS d FROM jurisdictions WHERE parent_jurisdiction_id IS NULL
    UNION ALL
    SELECT j.jurisdiction_id, p.d + 1 FROM jurisdictions j JOIN depth_of p ON j.parent_jurisdiction_id = p.jurisdiction_id
), chain AS (
    SELECT jurisdiction_id, jurisdiction_id AS ancestor_id FROM jurisdictions
    UNION ALL
    SELECT c.jurisdiction_id, j.parent_jurisdiction_id FROM chain c JOIN jurisdictions j ON j.jurisdiction_id = c.ancestor_id
    WHERE j.parent_jurisdiction_id IS NOT NULL
)
SELECT c.jurisdiction_id, c.ancestor_id, d.d AS ancestor_depth
FROM chain c JOIN depth_of d ON d.jurisdiction_id = c.ancestor_id;

-- The "local area" each jurisdiction belongs to: its ancestor at depth 2,
-- or itself when it sits higher than that.
CREATE OR REPLACE VIEW metric_local_area AS
SELECT j.jurisdiction_id,
       COALESCE((SELECT a.ancestor_id FROM metric_area_ancestors a
                 WHERE a.jurisdiction_id = j.jurisdiction_id AND a.ancestor_depth = 2), j.jurisdiction_id) AS local_area_id
FROM jurisdictions j;


-- ── 4. Definitions, version 1 ────────────────────────────────────────────

INSERT INTO metric_definitions (metric_key, category, name, definition, unit, is_headline, sort_order) VALUES
-- Headline KPIs
('mau',                 'headline',   'Monthly active users',          'People active in the 30 days ending on the last day of the period. Active means at least one counted play, vote, like, comment, follow, playlist add, direct message, download purchase or support payment. Opening a page alone does not count. Staff and test accounts, suspended accounts and closed accounts are never counted. Until 30 days have passed since launch day (November 21, 2026), the window starts on launch day.', 'count',   TRUE, 10),
('dau',                 'headline',   'Daily active users',            'People active on a given day (New York time), by the same test as monthly active users. For a period, the average across its days. DAU ÷ MAU shows how much of the audience comes back every day.', 'count',   TRUE, 20),
('mal',                 'headline',   'Monthly active listeners',      'People with at least one counted play in the 30 days ending on the last day of the period. A counted play is a play by a logged-in person who listened for 15 seconds (or a quarter of a song shorter than a minute). Artists playing their own songs are not counted.', 'count',   TRUE, 30),
('listen_hours_per_listener','headline','Listening hours per listener', 'Total hours heard in the period divided by monthly active listeners. Hours heard = song length × the share of the song heard. When the player never sends its "play finished" message, the play is counted at its 15-second minimum, so the figure leans low.', 'hours', TRUE, 40),
('voting_participation','headline',   'Voting participation',          'Share of monthly active users who cast at least one vote in the same 30 days.', 'percent', TRUE, 50),
('local_listening_share','headline',  'Local listening share',         'Share of counted plays where the listener and the song are from the same area: the same neighborhood, or neighborhoods inside the same area below the state level (Uptown and Downtown both count as Harlem).', 'percent', TRUE, 60),
('active_artists',      'headline',   'Monthly active artists',        'Artists whose songs got at least one counted play from someone else in the period.', 'count', TRUE, 70),
('gpv',                 'headline',   'Gross payments volume',         'Money fans paid artists through Unis in the period: download purchases plus direct support, counting only payments still completed (refunded and disputed payments are left out). The marketplace''s GMV; it is not Unis revenue.', 'usd', TRUE, 80),
('net_revenue',         'headline',   'Net revenue and revenue per user','What Unis keeps: its platform fees on downloads and support, plus its share of display ad revenue. Ad revenue is an estimate at a modeled $3.50 per 1,000 views until an ad network pays real revenue. Revenue per user = net revenue ÷ the average of monthly active users across the period''s days.', 'usd', TRUE, 90),
('retention_m3',        'headline',   'Month-3 retention',             'Of the people who signed up in a given month, the share who were active at least once in the third calendar month after it (signed up in December, active in March). Accounts created before launch day belong to the launch-month group.', 'percent', TRUE, 100),
-- Growth and acquisition
('signups',             'growth',     'New signups',                   'New accounts, listeners and artists separately. Staff and test accounts are left out.', 'count', FALSE, 200),
('signup_source',       'growth',     'Signup source mix',             'Share of signups that used a referral code, came from the waitlist (email matched), arrived through a campaign link, or none of these (organic).', 'percent', FALSE, 210),
('activation_rate',     'growth',     'Activation rate',               'Share of new signups with a counted play or a vote within 7 days of signing up. Signups from the last 6 days of a period are still inside their window when the period closes.', 'percent', FALSE, 220),
('referred_per_100_mau','growth',     'Referred signups per 100 MAU',  'Signups with a referral code in the period, per 100 monthly active users.', 'ratio', FALSE, 230),
('growth_accounting',   'growth',     'Growth accounting',             'People active in the calendar month, split into new (signed up this month), retained (also active last month), returning (active this month, not last month, signed up earlier) and lost (active last month, not this month). Quick ratio = (new + returning) ÷ lost.', 'count', FALSE, 240),
('account_closures',    'growth',     'Account closures',              'Accounts closed in the period.', 'count', FALSE, 250),
('waitlist_demand',     'growth',     'Waitlist demand by metro',      'Waitlist signups in the period by state and metro, and waitlist members who opened an account.', 'count', FALSE, 260),
-- Engagement
('plays',               'engagement', 'Counted plays',                 'Counted plays in the period, and per monthly active listener.', 'count', FALSE, 300),
('completion_rate',     'engagement', 'Completion rate',               'Share of counted plays heard to at least 80%.', 'percent', FALSE, 310),
('active_days',         'engagement', 'Active days per user',          'Average number of days in the calendar month each person active that month was active.', 'days', FALSE, 320),
('votes',               'engagement', 'Votes',                         'Votes cast, votes per voter, and votes by neighborhood and genre.', 'count', FALSE, 330),
('social_actions',      'engagement', 'Social actions',                'Likes, comments, follows and direct messages, each per 1,000 monthly active users.', 'ratio', FALSE, 340),
('playlist_activity',   'engagement', 'Playlist activity',             'Playlists created, songs added to playlists, and the share of plays started from a playlist.', 'count', FALSE, 350),
('play_sources',        'engagement', 'Play sources',                  'Share of counted plays by where they were started: feed, song page, playlist, Discover, Recommended, browse pages and others.', 'percent', FALSE, 360),
('listening_time',      'engagement', 'Listening by time',             'Counted plays by hour of day and day of week, New York time.', 'count', FALSE, 370),
('platform_mix',        'engagement', 'Platform mix',                  'Daily active users and counted plays on web, iPhone and Android. A person active on two platforms in a day counts once for each.', 'percent', FALSE, 380),
('logged_out_listening','engagement', 'Logged-out listening',          'Plays and listening hours by people who were not logged in, counted by an anonymous browser or app ID. Kept separate: never counted as users, never earns points, never added to a song''s play count.', 'count', FALSE, 390),
-- Catalog and artists
('catalog_size',        'catalog',    'Catalog size',                  'Playable songs at the end of the period (not removed, with audio, clean companion versions counted once), and new uploads in the period.', 'count', FALSE, 400),
('tagged_share',        'catalog',    'Tagged share',                  'Share of playable songs with a subgenre (or a written description) and a mood.', 'percent', FALSE, 410),
('catalog_reach',       'catalog',    'Catalog reach',                 'Share of playable songs with at least one counted play in the period.', 'percent', FALSE, 420),
('artist_concentration','catalog',    'Artist concentration',          'Share of counted plays that went to the top 10% of artists by plays. Lower means a healthier long tail.', 'percent', FALSE, 430),
('genre_mix',           'catalog',    'Genre mix',                     'Counted plays by genre, by primary subgenre and by primary mood.', 'count', FALSE, 440),
('artist_activation',   'catalog',    'Artist activation',             'New artists in the period, and of the artists who joined the month before, the share who reached 10 different listeners within 30 days of joining.', 'percent', FALSE, 450),
-- Artist economy and revenue
('payments_split',      'economy',    'Payments split',                'Gross payments volume split into download purchases and direct support.', 'usd', FALSE, 500),
('take_rate',           'economy',    'Take rate',                     'Unis platform fees on downloads and support divided by gross payments volume.', 'percent', FALSE, 510),
('paying_fans',         'economy',    'Paying fans',                   'People with a completed purchase or support payment in the period, and that number as a share of monthly active users.', 'count', FALSE, 520),
('average_payment',     'economy',    'Average payment',               'Gross payments volume divided by the number of completed payments.', 'usd', FALSE, 530),
('refunds_disputes',    'economy',    'Refunds and disputes',          'Payments made in the period that are now refunded or disputed, as a share of all payments made in the period, by count and by dollars.', 'percent', FALSE, 540),
('ad_views',            'economy',    'Ad views',                      'Display ad views, ad views per monthly active user, and estimated ad revenue split between Unis, supported artists and referrers. Estimated at $3.50 per 1,000 views.', 'count', FALSE, 550),
('artist_earnings',     'economy',    'Artist earnings',               'Money credited to artists: downloads and support after the Unis fee, plus the supported-artist share of estimated ad revenue.', 'usd', FALSE, 560),
('payouts',             'economy',    'Payouts',                       'Payouts completed in the period, their total, and failed payouts.', 'usd', FALSE, 570),
('revenue_per_user_area','economy',   'Revenue per user by area',      'Net revenue from people in each area divided by that area''s average monthly active users. Covers people 18 and over and people without a birth date.', 'usd', FALSE, 580),
-- Audience
('audience_who',        'audience',   'Who''s active',                 'Monthly active users by age band, gender and neighborhood. People under 18 appear only as one 13–17 headcount and are left out of every other split. Groups of fewer than 10 people are hidden.', 'count', FALSE, 600),
('audience_taste',      'audience',   'Taste by age',                  'Top subgenres and moods for each age band, 18 and over. Rows heard by fewer than 10 people are hidden.', 'count', FALSE, 610),
('audience_local_charts','audience',  'Local charts',                  'Top songs, artists and subgenres by the listener''s neighborhood and area, 18 and over. Rows heard by fewer than 10 people are hidden.', 'count', FALSE, 620),
('audience_when',       'audience',   'When people listen',            'Counted plays by hour of day for each age band, 18 and over.', 'count', FALSE, 630),
('audience_language',   'audience',   'Language mix',                  'Counted plays by the song''s language.', 'percent', FALSE, 640),
('audience_payers',     'audience',   'Who pays',                      'Paying fans by age band, gender and neighborhood, 18 and over. Groups of fewer than 10 people are hidden.', 'count', FALSE, 650),
-- Cohorts
('cohort_retention',    'cohorts',    'Retention table',               'For each signup month, the share of its members active at least once in each calendar month after it; split by referred or not and by listener or artist.', 'percent', FALSE, 700),
('cohort_listening',    'cohorts',    'Listening per cohort',          'Listening hours in each month per person still active from that signup month.', 'hours', FALSE, 710),
('cohort_money',        'cohorts',    'Money per cohort',              'Payments and net revenue per original member of each signup month, added up from signup to the end of each month. The start of lifetime value.', 'usd', FALSE, 720),
-- Integrity
('excluded_accounts',   'integrity',  'Excluded accounts',             'Accounts left out of every metric at the end of the period: flagged staff or test accounts, suspended accounts and closed accounts.', 'count', FALSE, 800),
('suspicious_plays',    'integrity',  'Suspicious plays',              'Share of counted plays from accounts with more than 300 counted plays in one day (about 15 hours of listening). Shown for review; not removed from other metrics.', 'percent', FALSE, 810),
('moderation',          'integrity',  'Moderation',                    'Suspensions active at the end of the period and DMCA claims received in the period.', 'count', FALSE, 820)
ON CONFLICT (metric_key) DO NOTHING;
