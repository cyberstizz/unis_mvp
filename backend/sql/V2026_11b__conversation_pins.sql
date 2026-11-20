-- ─────────────────────────────────────────────────────────────────────────────
-- V2026_11b — Pinned chats
--
-- HAND-APPLIED in the Supabase SQL editor. Safe to run more than once.
-- Run it BEFORE deploying the backend that reads these columns.
--
-- Each person can pin up to 3 chats to the top of their inbox. Pins are
-- private, so each side of a conversation has its own column, the same way
-- last_read_at_one / last_read_at_two work:
--   pinned_at_one  when participant_one pinned it (NULL = not pinned)
--   pinned_at_two  when participant_two pinned it (NULL = not pinned)
-- Pinned chats are listed in the order they were pinned.
--
-- No existing data changes: every conversation starts unpinned.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE conversations ADD COLUMN IF NOT EXISTS pinned_at_one TIMESTAMP NULL;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS pinned_at_two TIMESTAMP NULL;

-- Check: both columns exist and nothing is pinned yet (expect 0).
--   SELECT COUNT(*) FROM conversations
--   WHERE pinned_at_one IS NOT NULL OR pinned_at_two IS NOT NULL;
