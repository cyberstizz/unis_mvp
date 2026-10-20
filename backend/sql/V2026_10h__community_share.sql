-- ════════════════════════════════════════════════════════════════════════════
-- V2026_10h — The Unis Community Share
--
-- Replaces "every signed-in ad view pays your supported artist and your
-- referrers" (which Google's AdSense and AdMob rules forbid) with:
--
--   Each month Unis takes what it actually received for that month (ad
--   income, other income such as sponsors and Unis Plus, and its fees on song
--   sales and support), divides it by counted listening, and sends 15% of each
--   listener's part to their supported artist and 10 / 5 / 2% to the three
--   people above them in the referral chain. Unis keeps the rest.
--
-- What this script does:
--   1. song_plays.supported_artist_id — each play remembers which artist the
--      listener supported at that moment (same "stamped at the time" rule the
--      old ad views had, so a switch on the 1st never moves past money).
--   2. community_share_closes — one row per month that has been closed.
--   3. earnings_ledger — the money each person earned: one row per earner,
--      per listener, per month, per level. Balances are read from here.
--   4. payouts.from_ledger — payouts made before this change (test payouts
--      against the old simulated balances) stop counting against the new
--      balances; every payout from now on does.
--   5. Updates two metric descriptions that still described estimated ad
--      revenue.
--
-- The old ad_views rows are kept for history but no longer count anywhere.
-- Run in the Supabase SQL Editor (production) BEFORE deploying the backend.
-- Safe to run twice.
-- ════════════════════════════════════════════════════════════════════════════

-- 1. Which artist each play supported ─────────────────────────────────────────
ALTER TABLE song_plays ADD COLUMN IF NOT EXISTS supported_artist_id UUID;

-- This month's plays so far: stamp them with each listener's current artist,
-- so the first monthly close isn't missing the days before this deploy.
UPDATE song_plays sp
   SET supported_artist_id = u.supported_artist_id
  FROM users u
 WHERE u.user_id = sp.user_id
   AND sp.supported_artist_id IS NULL
   AND sp.played_at >= date_trunc('month', now());

-- 2. Months that have been closed ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS community_share_closes (
    close_id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    period_month       DATE          NOT NULL UNIQUE,         -- first day of the month closed
    ad_income          NUMERIC(14,2) NOT NULL DEFAULT 0,      -- AdSense / AdMob money received for that month
    other_income       NUMERIC(14,2) NOT NULL DEFAULT 0,      -- sponsors, Unis Plus, anything else
    fee_income         NUMERIC(14,2) NOT NULL DEFAULT 0,      -- Unis's fees on song sales and support
    total_revenue      NUMERIC(14,2) NOT NULL,
    counted_plays      NUMERIC(14,2) NOT NULL DEFAULT 0,      -- after the daily cap
    listeners          INTEGER       NOT NULL DEFAULT 0,
    earners            INTEGER       NOT NULL DEFAULT 0,
    paid_to_community  NUMERIC(14,6) NOT NULL DEFAULT 0,
    kept_by_unis       NUMERIC(14,6) NOT NULL DEFAULT 0,
    note               TEXT,
    closed_by          UUID,
    closed_at          TIMESTAMP     NOT NULL DEFAULT now()
);

-- 3. What each person earned ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS earnings_ledger (
    entry_id        UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID          NOT NULL,                   -- who earned it
    period_month    DATE          NOT NULL,                   -- the month it was earned in
    stream          VARCHAR(20)   NOT NULL
                    CHECK (stream IN ('supporter', 'referral_1', 'referral_2', 'referral_3', 'adjustment')),
    source_user_id  UUID,                                     -- whose listening produced it (none for adjustments)
    counted_plays   NUMERIC(10,2),                            -- that listener's counted plays behind it
    amount          NUMERIC(14,6) NOT NULL,                   -- dollars; adjustments may be negative
    close_id        UUID REFERENCES community_share_closes (close_id),
    note            TEXT,
    created_by      UUID,
    created_at      TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_earnings_ledger_user_month ON earnings_ledger (user_id, period_month);
CREATE INDEX IF NOT EXISTS idx_earnings_ledger_source     ON earnings_ledger (source_user_id);
CREATE INDEX IF NOT EXISTS idx_earnings_ledger_close      ON earnings_ledger (close_id);

-- Same as every Unis table: nothing reachable through Supabase's public API.
ALTER TABLE community_share_closes ENABLE ROW LEVEL SECURITY;
ALTER TABLE earnings_ledger        ENABLE ROW LEVEL SECURITY;

-- 4. Old payouts stop counting against new balances ──────────────────────────
-- Added with FALSE so every payout that already exists is marked "old", then
-- the default flips to TRUE so every new payout counts. Guarded, so running
-- this twice can't relabel new payouts.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'payouts' AND column_name = 'from_ledger'
    ) THEN
        ALTER TABLE payouts ADD COLUMN from_ledger BOOLEAN NOT NULL DEFAULT FALSE;
        ALTER TABLE payouts ALTER COLUMN from_ledger SET DEFAULT TRUE;
    END IF;
END
$$;

-- 5. Metric descriptions ──────────────────────────────────────────────────────
-- Three metrics described estimated ad revenue from ad views. They now read
-- real money from closed months. Only changed once (the WHERE skips rows that
-- already say this), so running the script twice doesn't bump the version.
UPDATE metric_definitions
   SET definition  = 'What Unis keeps: its platform fees on downloads and support, plus what it kept from each closed month after the Community Share. A month adds its ad and other income only once it has been closed in Admin, Community Share. Revenue per user = net revenue ÷ the average of monthly active users across the period''s days.',
       version     = version + 1,
       change_note = 'Community Share: real income from closed months replaced the $3.50-per-1,000-views estimate.'
 WHERE metric_key = 'net_revenue'
   AND definition NOT LIKE '%Community Share%';

UPDATE metric_definitions
   SET name        = 'Community Share',
       definition  = 'For closed months: the income Unis received and how the Community Share divided it between Unis, supported artists and referrers. A month shows nothing here until it has been closed.',
       version     = version + 1,
       change_note = 'Community Share: replaces estimated ad views and ad revenue.'
 WHERE metric_key = 'ad_views'
   AND definition NOT LIKE '%Community Share%';

UPDATE metric_definitions
   SET definition  = 'Money credited to artists: downloads and support after the Unis fee, plus their supported-artist share of each closed month''s Community Share.',
       version     = version + 1,
       change_note = 'Community Share: supported-artist money now comes from closed months.'
 WHERE metric_key = 'artist_earnings'
   AND definition NOT LIKE '%Community Share%';
