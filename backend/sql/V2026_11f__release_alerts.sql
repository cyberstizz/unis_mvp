-- ============================================================================
-- V2026_11f: Release alerts
--
-- When an artist publishes a release or uploads a song, their followers get
-- an alert: a "New releases" row in Messages and, at most once a day, an
-- email. Alerts are read per follower at view time (no row per follower), so
-- an artist with thousands of followers still writes one row.
--
--   release_alerts           one row per artist per day; a release and a song
--                            on the same day are grouped into it
--   follows.alert_level      per artist: ALL (every release, the default),
--                            MAJOR (albums, EPs and mixtapes only) or OFF
--   users.release_alerts_*   in-app and email switches, when the alerts were
--                            last looked at, and when the last email went out
--
-- Safe to run more than once.
-- ============================================================================

CREATE TABLE IF NOT EXISTS release_alerts (
    alert_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    artist_id    UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    alert_day    DATE NOT NULL,                      -- the day in New York
    release_id   UUID NULL REFERENCES releases(release_id) ON DELETE SET NULL,
    song_id      UUID NULL REFERENCES songs(song_id) ON DELETE SET NULL,
    song_count   INT NOT NULL DEFAULT 0,             -- songs uploaded that day, not on the release
    major        BOOLEAN NOT NULL DEFAULT FALSE,     -- an album, EP or mixtape
    note         VARCHAR(280) NULL,                  -- the artist's note to followers
    reach        INT NOT NULL DEFAULT 0,             -- followers it was sent to
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP NOT NULL DEFAULT now(),   -- last time something new was added
    CONSTRAINT release_alerts_one_per_day UNIQUE (artist_id, alert_day)
);

CREATE INDEX IF NOT EXISTS idx_release_alerts_artist_updated ON release_alerts (artist_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_release_alerts_updated ON release_alerts (updated_at DESC);

ALTER TABLE follows ADD COLUMN IF NOT EXISTS alert_level VARCHAR(8) NOT NULL DEFAULT 'ALL';
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'follows_alert_level_check') THEN
        ALTER TABLE follows ADD CONSTRAINT follows_alert_level_check CHECK (alert_level IN ('ALL', 'MAJOR', 'OFF'));
    END IF;
END $$;

ALTER TABLE users ADD COLUMN IF NOT EXISTS release_alerts_in_app BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS release_alerts_email BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS release_alerts_seen_at TIMESTAMP NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS release_alerts_emailed_at TIMESTAMP NULL;

CREATE INDEX IF NOT EXISTS idx_follows_followed_level ON follows (followed_id, alert_level);
