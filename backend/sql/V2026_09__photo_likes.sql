-- =============================================================================
-- V2026_09__photo_likes.sql — likes on profile gallery photos
--
-- Hand-apply in the Supabase SQL editor (prod) and your local Postgres.
-- Safe to re-run: every statement is IF NOT EXISTS.
--
-- Deliberately a separate table from `likes` (songs/videos): photo likes award
-- no points and must never leak into song/artist scoring queries, which read
-- `likes` by media_type.
-- =============================================================================

CREATE TABLE IF NOT EXISTS photo_likes (
    photo_id   UUID      NOT NULL REFERENCES artist_photos(photo_id) ON DELETE CASCADE,
    user_id    UUID      NOT NULL REFERENCES users(user_id)          ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (photo_id, user_id)
);

-- PK (photo_id, user_id) already serves "count likes for a photo" and
-- "did I like this". This one serves account deletion (DELETE ... WHERE user_id).
CREATE INDEX IF NOT EXISTS idx_photo_likes_user ON photo_likes (user_id);

-- Verify:
--   SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'photo_likes';
