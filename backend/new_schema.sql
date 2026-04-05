-- ============================================================================
-- UNIS PLAYLIST SYSTEM UPGRADE — Migration V2 (Supabase Compatible)
-- ============================================================================
-- Uses gen_random_uuid() instead of uuid_generate_v4() for Supabase.
--
-- SAFE TO RUN: All statements use IF NOT EXISTS / IF EXISTS guards.
-- BACKWARDS COMPATIBLE: Existing playlists become type='personal', visibility='private'.
-- ============================================================================


-- ============================================================================
-- PART 1: EVOLVE THE EXISTING `playlist` TABLE
-- ============================================================================

ALTER TABLE public.playlist
    ADD COLUMN IF NOT EXISTS type VARCHAR(20) DEFAULT 'personal' NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'playlist_type_check'
    ) THEN
        ALTER TABLE public.playlist
            ADD CONSTRAINT playlist_type_check
            CHECK (type IN ('personal', 'community', 'official', 'auto'));
    END IF;
END $$;

ALTER TABLE public.playlist
    ADD COLUMN IF NOT EXISTS visibility VARCHAR(20) DEFAULT 'private' NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'playlist_visibility_check'
    ) THEN
        ALTER TABLE public.playlist
            ADD CONSTRAINT playlist_visibility_check
            CHECK (visibility IN ('private', 'unlisted', 'public'));
    END IF;
END $$;

ALTER TABLE public.playlist
    ADD COLUMN IF NOT EXISTS description VARCHAR(500);

ALTER TABLE public.playlist
    ADD COLUMN IF NOT EXISTS cover_image_url VARCHAR(512);

ALTER TABLE public.playlist
    ADD COLUMN IF NOT EXISTS jurisdiction_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_playlist_jurisdiction'
    ) THEN
        ALTER TABLE public.playlist
            ADD CONSTRAINT fk_playlist_jurisdiction
            FOREIGN KEY (jurisdiction_id) REFERENCES public.jurisdictions(jurisdiction_id);
    END IF;
END $$;

ALTER TABLE public.playlist
    ADD COLUMN IF NOT EXISTS max_songs INTEGER DEFAULT 5000 NOT NULL;

ALTER TABLE public.playlist
    ADD COLUMN IF NOT EXISTS is_auto_populated BOOLEAN DEFAULT FALSE NOT NULL;

ALTER TABLE public.playlist
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

ALTER TABLE public.playlist
    ADD COLUMN IF NOT EXISTS follower_count INTEGER DEFAULT 0 NOT NULL;

ALTER TABLE public.playlist
    ADD COLUMN IF NOT EXISTS song_count INTEGER DEFAULT 0 NOT NULL;


-- ============================================================================
-- PART 2: EVOLVE `playlist_items` TABLE
-- ============================================================================

ALTER TABLE public.playlist_items
    ADD COLUMN IF NOT EXISTS added_by_user_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_playlist_items_added_by'
    ) THEN
        ALTER TABLE public.playlist_items
            ADD CONSTRAINT fk_playlist_items_added_by
            FOREIGN KEY (added_by_user_id) REFERENCES public.users(user_id);
    END IF;
END $$;

ALTER TABLE public.playlist_items
    ADD COLUMN IF NOT EXISTS upvotes INTEGER DEFAULT 0 NOT NULL;

ALTER TABLE public.playlist_items
    ADD COLUMN IF NOT EXISTS downvotes INTEGER DEFAULT 0 NOT NULL;

ALTER TABLE public.playlist_items
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'active' NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'playlist_items_status_check'
    ) THEN
        ALTER TABLE public.playlist_items
            ADD CONSTRAINT playlist_items_status_check
            CHECK (status IN ('active', 'pending', 'removed'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_playlist_song'
    ) THEN
        ALTER TABLE public.playlist_items
            ADD CONSTRAINT uq_playlist_song UNIQUE (playlist_id, song_id);
    END IF;
END $$;


-- ============================================================================
-- PART 3: NEW TABLE — `playlist_follows`
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.playlist_follows (
    follow_id       UUID DEFAULT gen_random_uuid() NOT NULL,
    playlist_id     UUID NOT NULL,
    user_id         UUID NOT NULL,
    followed_at     TIMESTAMP DEFAULT NOW() NOT NULL,

    CONSTRAINT playlist_follows_pkey PRIMARY KEY (follow_id),
    CONSTRAINT uq_playlist_follow UNIQUE (playlist_id, user_id),
    CONSTRAINT fk_playlist_follows_playlist
        FOREIGN KEY (playlist_id) REFERENCES public.playlist(playlist_id) ON DELETE CASCADE,
    CONSTRAINT fk_playlist_follows_user
        FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE
);


-- ============================================================================
-- PART 4: NEW TABLE — `playlist_votes`
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.playlist_votes (
    vote_id             UUID DEFAULT gen_random_uuid() NOT NULL,
    playlist_item_id    UUID NOT NULL,
    user_id             UUID NOT NULL,
    vote_type           VARCHAR(10) NOT NULL,
    voted_at            TIMESTAMP DEFAULT NOW() NOT NULL,

    CONSTRAINT playlist_votes_pkey PRIMARY KEY (vote_id),
    CONSTRAINT uq_playlist_vote UNIQUE (playlist_item_id, user_id),
    CONSTRAINT playlist_votes_type_check CHECK (vote_type IN ('up', 'down')),
    CONSTRAINT fk_playlist_votes_item
        FOREIGN KEY (playlist_item_id) REFERENCES public.playlist_items(playlist_item_id) ON DELETE CASCADE,
    CONSTRAINT fk_playlist_votes_user
        FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE
);


-- ============================================================================
-- PART 5: NEW TABLE — `playlist_activity`
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.playlist_activity (
    activity_id     UUID DEFAULT gen_random_uuid() NOT NULL,
    playlist_id     UUID NOT NULL,
    user_id         UUID NOT NULL,
    action_type     VARCHAR(30) NOT NULL,
    target_song_id  UUID,
    details         VARCHAR(500),
    created_at      TIMESTAMP DEFAULT NOW() NOT NULL,

    CONSTRAINT playlist_activity_pkey PRIMARY KEY (activity_id),
    CONSTRAINT fk_playlist_activity_playlist
        FOREIGN KEY (playlist_id) REFERENCES public.playlist(playlist_id) ON DELETE CASCADE,
    CONSTRAINT fk_playlist_activity_user
        FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_playlist_activity_song
        FOREIGN KEY (target_song_id) REFERENCES public.songs(song_id) ON DELETE SET NULL
);


-- ============================================================================
-- PART 6: NEW TABLE — `blocked_songs`
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.blocked_songs (
    block_id    UUID DEFAULT gen_random_uuid() NOT NULL,
    user_id     UUID NOT NULL,
    song_id     UUID NOT NULL,
    blocked_at  TIMESTAMP DEFAULT NOW() NOT NULL,

    CONSTRAINT blocked_songs_pkey PRIMARY KEY (block_id),
    CONSTRAINT uq_blocked_song UNIQUE (user_id, song_id),
    CONSTRAINT fk_blocked_songs_user
        FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_blocked_songs_song
        FOREIGN KEY (song_id) REFERENCES public.songs(song_id) ON DELETE CASCADE
);


-- ============================================================================
-- PART 7: INDEXES
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_playlist_type
    ON public.playlist USING btree (type);

CREATE INDEX IF NOT EXISTS idx_playlist_visibility
    ON public.playlist USING btree (visibility)
    WHERE visibility IN ('public', 'unlisted');

CREATE INDEX IF NOT EXISTS idx_playlist_jurisdiction
    ON public.playlist USING btree (jurisdiction_id)
    WHERE jurisdiction_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_playlist_type_visibility
    ON public.playlist USING btree (type, visibility);

CREATE INDEX IF NOT EXISTS idx_playlist_deleted_at
    ON public.playlist USING btree (deleted_at)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_playlist_items_status
    ON public.playlist_items USING btree (status)
    WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS idx_playlist_items_added_by
    ON public.playlist_items USING btree (added_by_user_id)
    WHERE added_by_user_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_playlist_follows_user
    ON public.playlist_follows USING btree (user_id);

CREATE INDEX IF NOT EXISTS idx_playlist_follows_playlist
    ON public.playlist_follows USING btree (playlist_id);

CREATE INDEX IF NOT EXISTS idx_playlist_votes_item
    ON public.playlist_votes USING btree (playlist_item_id);

CREATE INDEX IF NOT EXISTS idx_playlist_votes_user
    ON public.playlist_votes USING btree (user_id);

CREATE INDEX IF NOT EXISTS idx_playlist_activity_playlist
    ON public.playlist_activity USING btree (playlist_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_playlist_activity_user
    ON public.playlist_activity USING btree (user_id);

CREATE INDEX IF NOT EXISTS idx_blocked_songs_user
    ON public.blocked_songs USING btree (user_id);

CREATE INDEX IF NOT EXISTS idx_blocked_songs_song
    ON public.blocked_songs USING btree (song_id);


-- ============================================================================
-- PART 8: BACKFILL EXISTING DATA
-- ============================================================================

UPDATE public.playlist
SET type = 'personal',
    visibility = 'private'
WHERE type IS NULL OR type = 'personal';

UPDATE public.playlist_items pi
SET added_by_user_id = p.created_by
FROM public.playlist p
WHERE pi.playlist_id = p.playlist_id
  AND pi.added_by_user_id IS NULL;

UPDATE public.playlist p
SET song_count = (
    SELECT COUNT(*)
    FROM public.playlist_items pi
    WHERE pi.playlist_id = p.playlist_id
      AND pi.status = 'active'
);


-- ============================================================================
-- PART 9: COMMENTS
-- ============================================================================

COMMENT ON TABLE public.playlist IS 'User-created, community, official, and auto-generated playlists';
COMMENT ON COLUMN public.playlist.type IS 'personal | community | official | auto';
COMMENT ON COLUMN public.playlist.visibility IS 'private | unlisted | public';
COMMENT ON COLUMN public.playlist.jurisdiction_id IS 'Required for community playlists; optional for others';
COMMENT ON COLUMN public.playlist.is_auto_populated IS 'True for official playlists auto-populated from award winners';
COMMENT ON COLUMN public.playlist.max_songs IS 'Maximum tracks allowed. Default 5000.';
COMMENT ON COLUMN public.playlist.follower_count IS 'Denormalized count from playlist_follows. Updated by service layer.';
COMMENT ON COLUMN public.playlist.song_count IS 'Denormalized count of active tracks. Updated on add/remove.';

COMMENT ON TABLE public.playlist_follows IS 'Users following public/unlisted playlists for updates';
COMMENT ON TABLE public.playlist_votes IS 'Community playlist: per-user up/down vote on suggested tracks';
COMMENT ON TABLE public.playlist_activity IS 'Transparent activity log for community playlists';
COMMENT ON TABLE public.blocked_songs IS 'Per-user song blocking: excluded from auto playlists and recommendations';

COMMENT ON COLUMN public.playlist_items.added_by_user_id IS 'User who added/suggested this track. Owner for personal; contributor for community.';
COMMENT ON COLUMN public.playlist_items.status IS 'active = in playlist | pending = awaiting community votes | removed = voted out or curator-removed';
COMMENT ON COLUMN public.playlist_items.upvotes IS 'Community playlists only: cached upvote count';
COMMENT ON COLUMN public.playlist_items.downvotes IS 'Community playlists only: cached downvote count';