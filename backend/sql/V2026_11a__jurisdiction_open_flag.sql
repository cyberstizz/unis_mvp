-- ─────────────────────────────────────────────────────────────────────────────
-- V2026_11a — One switch for "is this neighborhood open"
--
-- HAND-APPLIED in the Supabase SQL editor. Safe to run more than once.
--
-- jurisdictions.voting_enabled is now the ONE flag that says a jurisdiction is
-- open. Voting, standings and award closes already read it; after this change
-- the Find page map reads it too (GET /api/v1/jurisdictions/open and the
-- isActive field on /children/detailed). Nothing is hardcoded anymore, in the
-- backend (JurisdictionService) or the web (findpage.jsx).
--
-- This file changes no data that is already right. It only:
--   1. makes the flag never NULL (NULL used to read as "closed" in some places
--      and was ambiguous), and
--   2. makes sure Harlem, Uptown Harlem and Downtown Harlem are open, which is
--      what the hardcoded lists said.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 0. LOOK FIRST: what is open right now? ──────────────────────────────────
-- Every row this returns will show as open on the Find page map after deploy.
-- If something is on here you don't expect (for example Unis or Manhattan),
-- decide before deploying.
--
--   SELECT name, depth, voting_enabled
--   FROM jurisdictions
--   WHERE voting_enabled IS DISTINCT FROM false
--   ORDER BY depth, name;

-- ── 1. No NULLs: NULL means closed ──────────────────────────────────────────
UPDATE jurisdictions SET voting_enabled = false WHERE voting_enabled IS NULL;
ALTER TABLE jurisdictions ALTER COLUMN voting_enabled SET DEFAULT false;
ALTER TABLE jurisdictions ALTER COLUMN voting_enabled SET NOT NULL;

-- ── 2. The launch neighborhoods are open ────────────────────────────────────
UPDATE jurisdictions SET voting_enabled = true
WHERE name IN ('Harlem', 'Uptown Harlem', 'Downtown Harlem')
  AND voting_enabled = false;

-- ── Verify (expect three rows, all true) ────────────────────────────────────
--   SELECT name, voting_enabled FROM jurisdictions
--   WHERE name IN ('Harlem', 'Uptown Harlem', 'Downtown Harlem');

-- ─────────────────────────────────────────────────────────────────────────────
-- HOW TO OPEN A NEIGHBORHOOD LATER (no code change, no deploy):
--
--   UPDATE jurisdictions SET voting_enabled = true WHERE name = 'Washington Heights';
--
-- The map picks it up within 10 minutes (the jurisdictions cache), or right
-- away after a Railway restart. Closing one is the same with false.
--
-- Note: other parts of the web app still keep their own Harlem lists (vote
-- dropdowns, charts, Discover, etc.). See backend/docs/OPEN_ISSUES.md.
-- ─────────────────────────────────────────────────────────────────────────────
