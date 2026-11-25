-- =============================================================================
-- V2026_11g  Volume levelling, Unis Mix, Liked Songs and Recently played
-- =============================================================================
-- Run this in the Supabase SQL editor BEFORE deploying the backend.
-- Safe to run more than once (every statement is IF NOT EXISTS).
--
-- 1. songs: what the server measures once per song (ffmpeg + Unis's own
--    tempo/key detector). The audio file itself is never changed.
--      loudness_lufs     integrated loudness (ITU-R BS.1770 / EBU R128)
--      true_peak_db      loudest true peak, dBTP
--      loudness_range    LRA, in LU
--      bpm               tempo, NULL when the song has no steady beat
--      bpm_confidence    0..1, how sure the detector is
--      beat_offset_ms    time of the first beat
--      downbeat_ms       time of the first downbeat (beat 1 of a bar)
--      music_key         e.g. 'A minor'
--      camelot           e.g. '8A' (DJ key wheel)
--      lead_silence_ms   silence before the music starts
--      intro_end_ms      where the intro ends
--      outro_start_ms    where the outro begins
--      tail_silence_ms   where the music ends (silence after this)
--      waveform          1000 peaks, base64 (one byte each)
--      analyzed_at       NULL = not measured yet (the catch-up job picks it up)
--      analysis_attempts tries so far (the job gives up after 3)
--      analysis_error    the last error, for the admin
--
-- 2. playlist.mix_enabled + playlist_transitions: the playlist owner's own
--    transitions (style, length in bars, start shift in bars). Keyed by the
--    pair of songs, so reordering keeps any edit whose pair still meets.
--
-- 3. recent_play_hides: "Remove from recently played" hides a song from the
--    listener's Recently played only. Plays stay counted. Playing the song
--    again later brings it back (only plays before hidden_at are hidden).
-- =============================================================================

ALTER TABLE songs ADD COLUMN IF NOT EXISTS loudness_lufs     NUMERIC(5,2);
ALTER TABLE songs ADD COLUMN IF NOT EXISTS true_peak_db      NUMERIC(5,2);
ALTER TABLE songs ADD COLUMN IF NOT EXISTS loudness_range    NUMERIC(5,2);
ALTER TABLE songs ADD COLUMN IF NOT EXISTS bpm               NUMERIC(6,2);
ALTER TABLE songs ADD COLUMN IF NOT EXISTS bpm_confidence    NUMERIC(4,3);
ALTER TABLE songs ADD COLUMN IF NOT EXISTS beat_offset_ms    INTEGER;
ALTER TABLE songs ADD COLUMN IF NOT EXISTS downbeat_ms       INTEGER;
ALTER TABLE songs ADD COLUMN IF NOT EXISTS music_key         VARCHAR(16);
ALTER TABLE songs ADD COLUMN IF NOT EXISTS camelot           VARCHAR(3);
ALTER TABLE songs ADD COLUMN IF NOT EXISTS lead_silence_ms   INTEGER;
ALTER TABLE songs ADD COLUMN IF NOT EXISTS intro_end_ms      INTEGER;
ALTER TABLE songs ADD COLUMN IF NOT EXISTS outro_start_ms    INTEGER;
ALTER TABLE songs ADD COLUMN IF NOT EXISTS tail_silence_ms   INTEGER;
ALTER TABLE songs ADD COLUMN IF NOT EXISTS waveform          TEXT;
ALTER TABLE songs ADD COLUMN IF NOT EXISTS analyzed_at       TIMESTAMPTZ;
ALTER TABLE songs ADD COLUMN IF NOT EXISTS analysis_attempts SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE songs ADD COLUMN IF NOT EXISTS analysis_error    VARCHAR(300);

-- The catch-up job's queue: songs still to measure, oldest first.
CREATE INDEX IF NOT EXISTS idx_songs_needs_analysis
    ON songs (created_at)
    WHERE analyzed_at IS NULL AND deleted_at IS NULL;

ALTER TABLE playlist ADD COLUMN IF NOT EXISTS mix_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS playlist_transitions (
    playlist_id   UUID        NOT NULL REFERENCES playlist(playlist_id) ON DELETE CASCADE,
    from_song_id  UUID        NOT NULL,
    to_song_id    UUID        NOT NULL,
    style         VARCHAR(12) NOT NULL DEFAULT 'auto'
                  CHECK (style IN ('auto','fade','rise','bass','echo','cut','none')),
    bars          SMALLINT    NOT NULL DEFAULT 4 CHECK (bars IN (1,2,4,8,16)),
    start_shift   SMALLINT    NOT NULL DEFAULT 0 CHECK (start_shift BETWEEN 0 AND 32),
    updated_by    UUID,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (playlist_id, from_song_id, to_song_id)
);

CREATE TABLE IF NOT EXISTS recent_play_hides (
    user_id    UUID        NOT NULL,
    song_id    UUID        NOT NULL,
    hidden_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, song_id)
);

-- Recently played reads one listener's newest plays.
CREATE INDEX IF NOT EXISTS idx_song_plays_user_played
    ON song_plays (user_id, played_at DESC);

-- Liked Songs reads one listener's song likes, newest first.
CREATE INDEX IF NOT EXISTS idx_likes_user_song
    ON likes (user_id, created_at DESC)
    WHERE media_type = 'song';
