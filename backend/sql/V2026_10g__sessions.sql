-- ════════════════════════════════════════════════════════════════════════════
-- V2026_10g — Signing out really ends a session
--
-- Before: a sign-in token stayed valid for its full 24 hours, even after the
-- person signed out or changed their password. Now:
--
--   users.session_version  goes up by one whenever every session of an account
--                          must end (password changed or reset, account
--                          deleted, staff two-factor reset). Each token carries
--                          the version it was issued under; an older one is
--                          refused.
--   revoked_sessions       one row per token signed out from one device. Rows
--                          are deleted once the token would have expired anyway
--                          (SessionService cleans up nightly).
--
-- Run this in the Supabase SQL Editor (production project) BEFORE deploying the
-- backend that reads it. Safe to run twice. Adding a column with a constant
-- default is instant in Postgres; no table rewrite, no downtime.
-- ════════════════════════════════════════════════════════════════════════════

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS session_version INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS revoked_sessions (
    token_id    TEXT        PRIMARY KEY,          -- the token's "jti" (random ID)
    user_id     UUID,
    expires_at  TIMESTAMPTZ NOT NULL,             -- when the token would have expired
    revoked_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_revoked_sessions_expires_at
    ON revoked_sessions (expires_at);

-- Same as every Unis table: no access through Supabase's public Data API.
-- The backend connects as the database owner and isn't affected.
ALTER TABLE revoked_sessions ENABLE ROW LEVEL SECURITY;
