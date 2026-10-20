-- ─────────────────────────────────────────────────────────────────────────────
-- V2026_10j — Unis Plus (the ad-free plan) and local sponsors
--
-- Run AFTER V2026_10h__community_share.sql and V2026_10i__fee_gate.sql.
-- Safe to run more than once.
--
-- Unis Plus: $4.99 a month through Stripe Billing (price set in Stripe).
--   • plus_subscriptions: one row per user who has ever started checkout;
--     Stripe's webhooks keep status and the paid-through date current.
--     A user is Plus while status is active/trialing/past_due and the paid
--     period hasn't ended.
--   • plus_payments: one row per paid invoice (Stripe's invoice id, so a
--     webhook delivered twice can't count twice). At the monthly Community
--     Share close, 60% of each payment (after Stripe's card and Billing fees)
--     goes to the artists that subscriber played that month, by counted plays
--     — or to their supported artist if they played no one. Unis's 40% joins
--     the Community Share pot. settled_close_id marks which close paid it.
--
-- Local sponsors: a shop owner scans a QR code, picks Uptown, Downtown or all
-- of Harlem and a week, uploads a logo and a line of text, and pays by card on
-- Unis's own Stripe account. An admin approves it (or rejects it, which
-- refunds it), and it runs in the ad slots labeled "Sponsored · Local".
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Unis Plus ───────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS plus_subscriptions (
    user_id                 UUID PRIMARY KEY REFERENCES users(user_id),
    stripe_customer_id      TEXT NOT NULL UNIQUE,
    stripe_subscription_id  TEXT UNIQUE,
    status                  TEXT NOT NULL DEFAULT 'incomplete',  -- Stripe's subscription status
    current_period_end      TIMESTAMP,                           -- paid through (server time)
    cancel_at_period_end    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS plus_payments (
    invoice_id           TEXT PRIMARY KEY,                       -- Stripe invoice id
    user_id              UUID NOT NULL REFERENCES users(user_id),
    supported_artist_id  UUID,                                   -- the subscriber's artist when they paid
    amount_paid          INTEGER NOT NULL,                       -- cents charged
    stripe_fees          INTEGER NOT NULL,                       -- cents: card 2.9% + 30¢ and Billing 0.7%
    net_amount           INTEGER NOT NULL,                       -- amount_paid - stripe_fees
    period_start         TIMESTAMP,
    period_end           TIMESTAMP,
    paid_at              TIMESTAMP NOT NULL,                     -- server time
    status               TEXT NOT NULL DEFAULT 'paid' CHECK (status IN ('paid', 'refunded')),
    settled_close_id     UUID,                                   -- community_share_closes.close_id
    created_at           TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_plus_payments_unsettled
    ON plus_payments (paid_at) WHERE settled_close_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_plus_payments_user ON plus_payments (user_id);

-- The ledger gets one more stream: an artist's 60% of Unis Plus fees.
ALTER TABLE earnings_ledger DROP CONSTRAINT IF EXISTS earnings_ledger_stream_check;
ALTER TABLE earnings_ledger ADD CONSTRAINT earnings_ledger_stream_check
    CHECK (stream IN ('supporter', 'referral_1', 'referral_2', 'referral_3', 'adjustment', 'plus_artist'));

-- Each close also records what Unis Plus brought in and paid out.
ALTER TABLE community_share_closes ADD COLUMN IF NOT EXISTS plus_income     NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE community_share_closes ADD COLUMN IF NOT EXISTS plus_to_artists NUMERIC(14,6) NOT NULL DEFAULT 0;

-- ── Local sponsors ──────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS sponsor_campaigns (
    campaign_id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_name             TEXT NOT NULL,
    contact_email             TEXT NOT NULL,
    headline                  TEXT NOT NULL,
    link_url                  TEXT NOT NULL,
    logo_url                  TEXT,
    area                      TEXT NOT NULL CHECK (area IN ('uptown', 'downtown', 'harlem')),
    week_start                DATE NOT NULL,                     -- a Monday
    weeks                     INTEGER NOT NULL CHECK (weeks BETWEEN 1 AND 4),
    amount_cents              INTEGER NOT NULL,
    status                    TEXT NOT NULL DEFAULT 'pending_payment'
                              CHECK (status IN ('pending_payment', 'pending_review', 'approved', 'rejected', 'refunded', 'expired')),
    referral_code             TEXT,                              -- the street-team code on the QR, if any
    stripe_session_id         TEXT UNIQUE,
    stripe_payment_intent_id  TEXT,
    paid_at                   TIMESTAMP,
    review_note               TEXT,
    reviewed_by               UUID,
    reviewed_at               TIMESTAMP,
    impressions               INTEGER NOT NULL DEFAULT 0,
    clicks                    INTEGER NOT NULL DEFAULT 0,
    report_sent_at            TIMESTAMP,                         -- the "how it did" email after it ends
    created_at                TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sponsor_campaigns_live
    ON sponsor_campaigns (area, week_start) WHERE status = 'approved';
CREATE INDEX IF NOT EXISTS idx_sponsor_campaigns_status ON sponsor_campaigns (status, created_at);

-- Server-only tables, like the rest of the money tables.
ALTER TABLE plus_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE plus_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE sponsor_campaigns ENABLE ROW LEVEL SECURITY;
