-- ─────────────────────────────────────────────────────────────────────────────
-- V2026_11d — Timed (synced) lyrics
--
-- HAND-APPLIED in the Supabase SQL editor. Safe to run more than once.
-- Run it BEFORE deploying the backend that reads this column.
--
-- songs.lyrics stays as it is: the plain words, shown on the song page.
-- songs.synced_lyrics is new: the same words with a start time on each line,
-- stored as LRC text, one line per lyric line:
--     [00:12.40]Streetlights hum on Lenox
--     [00:15.90]Corner store still open late
-- NULL means the song has no timing yet (its lyrics show without moving).
--
-- No existing data changes: every song starts with no timing.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE songs ADD COLUMN IF NOT EXISTS synced_lyrics TEXT NULL;

-- Check: the column exists and no song has timing yet (expect 0).
--   SELECT COUNT(*) FROM songs WHERE synced_lyrics IS NOT NULL;
