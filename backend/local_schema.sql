--
-- PostgreSQL database dump
--

-- Dumped from database version 16.4
-- Dumped by pg_dump version 16.4

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

-- *not* creating schema, since initdb creates it


--
-- Name: uuid-ossp; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;


--
-- Name: EXTENSION "uuid-ossp"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION "uuid-ossp" IS 'generate universally unique identifiers (UUIDs)';


--
-- Name: prevent_user_jurisdiction_change(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.prevent_user_jurisdiction_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- Only block if OLD jurisdiction was set AND it's being changed
    IF OLD.jurisdiction_id IS NOT NULL 
       AND OLD.jurisdiction_id IS DISTINCT FROM NEW.jurisdiction_id THEN
        RAISE EXCEPTION 'User jurisdiction cannot be changed once set. User ID: %. Current jurisdiction: %. Attempted change to: %', 
            OLD.user_id, OLD.jurisdiction_id, NEW.jurisdiction_id;
    END IF;
    RETURN NEW; -- Allow the update to proceed (for other fields)
END;
$$;


--
-- Name: prevent_vote_modification(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.prevent_vote_modification() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'Votes cannot be modified or deleted. Vote ID: %. This is a permanent record.', OLD.vote_id;
    RETURN NULL; -- Never reached, but required for function signature
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: account_suspensions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.account_suspensions (
    suspension_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    user_id uuid NOT NULL,
    suspended_by uuid NOT NULL,
    reason text NOT NULL,
    suspension_type character varying(20) NOT NULL,
    expires_at timestamp without time zone,
    lifted_at timestamp without time zone,
    lifted_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT account_suspensions_suspension_type_check CHECK (((suspension_type)::text = ANY ((ARRAY['temporary'::character varying, 'permanent'::character varying])::text[])))
);


--
-- Name: ad_views; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ad_views (
    ad_view_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    user_id uuid,
    artist_id uuid,
    ad_id uuid,
    supported_artist_id uuid,
    referred_artist_id uuid,
    revenue_share numeric(38,2) DEFAULT 0,
    viewed_at timestamp without time zone DEFAULT now(),
    duration_secs integer
);


--
-- Name: admin_roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.admin_roles (
    admin_role_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    user_id uuid NOT NULL,
    role_level character varying(20) NOT NULL,
    is_protected boolean DEFAULT false NOT NULL,
    granted_by uuid,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT admin_roles_role_level_check CHECK (((role_level)::text = ANY ((ARRAY['super_admin'::character varying, 'admin'::character varying, 'moderator'::character varying])::text[])))
);


--
-- Name: awards; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.awards (
    award_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    target_type character varying(255) NOT NULL,
    target_id uuid NOT NULL,
    genre_id uuid,
    jurisdiction_id uuid,
    interval_id uuid,
    award_date date NOT NULL,
    votes_count integer DEFAULT 0,
    engagement_score integer DEFAULT 0,
    weight integer DEFAULT 100,
    created_at timestamp without time zone DEFAULT now(),
    caption character varying(255),
    determination_method character varying(20),
    winner_seniority timestamp without time zone,
    tied_candidates_count integer DEFAULT 0,
    tiebreaker_details text,
    weighted_points integer DEFAULT 0,
    plays_count integer DEFAULT 0,
    likes_count integer DEFAULT 0,
    CONSTRAINT awards_target_type_check CHECK (((target_type)::text = ANY (ARRAY[('artist'::character varying)::text, ('song'::character varying)::text, ('video'::character varying)::text])))
);


--
-- Name: comments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.comments (
    comment_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    song_id uuid NOT NULL,
    user_id uuid NOT NULL,
    parent_comment_id uuid,
    content text NOT NULL,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    deleted_at timestamp without time zone,
    CONSTRAINT chk_no_self_reply CHECK (((parent_comment_id IS NULL) OR (parent_comment_id <> comment_id)))
);


--
-- Name: TABLE comments; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.comments IS 'User comments on songs with support for threaded replies';


--
-- Name: COLUMN comments.parent_comment_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.comments.parent_comment_id IS 'NULL for top-level comments, populated for replies';


--
-- Name: default_votes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.default_votes (
    default_vote_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    user_id uuid NOT NULL,
    target_type character varying(20) NOT NULL,
    target_id uuid NOT NULL,
    genre_id uuid,
    jurisdiction_id uuid,
    interval_id uuid,
    is_active boolean DEFAULT true,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now(),
    CONSTRAINT default_votes_target_type_check CHECK (((target_type)::text = ANY ((ARRAY['artist'::character varying, 'song'::character varying])::text[])))
);


--
-- Name: dmca_claims; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dmca_claims (
    claim_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    claimant_name character varying(255) NOT NULL,
    claimant_email character varying(255) NOT NULL,
    claimant_phone character varying(50),
    claimant_company character varying(255),
    copyright_owner character varying(255) NOT NULL,
    work_description text NOT NULL,
    original_work_url character varying(512),
    infringing_song_id uuid,
    infringing_url character varying(512) NOT NULL,
    status character varying(20) DEFAULT 'submitted'::character varying NOT NULL,
    assigned_to uuid,
    resolution_notes text,
    resolved_by uuid,
    resolved_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT dmca_claims_status_check CHECK (((status)::text = ANY ((ARRAY['submitted'::character varying, 'reviewing'::character varying, 'upheld'::character varying, 'rejected'::character varying, 'counter_pending'::character varying, 'resolved'::character varying])::text[])))
);


--
-- Name: dmca_counter_notices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dmca_counter_notices (
    counter_notice_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    claim_id uuid NOT NULL,
    respondent_user_id uuid NOT NULL,
    respondent_name character varying(255) NOT NULL,
    respondent_email character varying(255) NOT NULL,
    statement text NOT NULL,
    consent_to_jurisdiction boolean NOT NULL,
    signature character varying(255) NOT NULL,
    status character varying(20) DEFAULT 'filed'::character varying NOT NULL,
    filed_at timestamp without time zone DEFAULT now() NOT NULL,
    restore_eligible_at timestamp without time zone,
    resolved_at timestamp without time zone,
    CONSTRAINT dmca_counter_notices_status_check CHECK (((status)::text = ANY ((ARRAY['filed'::character varying, 'waiting_period'::character varying, 'content_restored'::character varying, 'lawsuit_filed'::character varying])::text[])))
);


--
-- Name: follows; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.follows (
    id uuid NOT NULL,
    created_at timestamp without time zone,
    followed_id uuid NOT NULL,
    follower_id uuid NOT NULL
);


--
-- Name: genres; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.genres (
    genre_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    name character varying(255) NOT NULL,
    created_at timestamp without time zone DEFAULT now()
);


--
-- Name: jurisdictions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.jurisdictions (
    jurisdiction_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    name character varying(255) NOT NULL,
    polygon character varying(255),
    parent_jurisdiction_id uuid,
    bio character varying(255),
    created_at timestamp without time zone DEFAULT now(),
    symbol_url character varying(255),
    depth integer,
    path text,
    voting_enabled boolean DEFAULT false
);


--
-- Name: likes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.likes (
    like_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    media_type character varying(255),
    media_id uuid NOT NULL,
    user_id uuid,
    created_at timestamp without time zone DEFAULT now(),
    CONSTRAINT likes_media_type_check CHECK (((media_type)::text = ANY (ARRAY[('song'::character varying)::text, ('video'::character varying)::text])))
);


--
-- Name: moderation_actions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.moderation_actions (
    action_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    performed_by uuid NOT NULL,
    action_type character varying(50) NOT NULL,
    target_type character varying(20) NOT NULL,
    target_id uuid NOT NULL,
    reason text,
    details text,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT moderation_actions_target_type_check CHECK (((target_type)::text = ANY ((ARRAY['user'::character varying, 'song'::character varying, 'video'::character varying, 'comment'::character varying, 'dmca_claim'::character varying])::text[])))
);


--
-- Name: password_reset_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.password_reset_tokens (
    token_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    user_id uuid NOT NULL,
    token character varying(255) NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    used_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: playlist; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.playlist (
    playlist_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    name character varying(255) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now()
);


--
-- Name: playlist_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.playlist_items (
    playlist_item_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    playlist_id uuid NOT NULL,
    song_id uuid NOT NULL,
    "position" integer NOT NULL,
    added_at timestamp without time zone DEFAULT now()
);


--
-- Name: referrals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.referrals (
    referral_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    referrer_id uuid,
    referred_id uuid,
    created_at timestamp without time zone DEFAULT now()
);


--
-- Name: song_plays; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.song_plays (
    play_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    song_id uuid,
    user_id uuid,
    played_at timestamp without time zone DEFAULT now(),
    duration_secs integer
);


--
-- Name: songs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.songs (
    song_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    artist_id uuid NOT NULL,
    title character varying(255) NOT NULL,
    genre_id uuid,
    file_url character varying(255),
    score integer DEFAULT 0 NOT NULL,
    description character varying(255),
    duration integer,
    created_at timestamp without time zone DEFAULT now(),
    level character varying(255),
    artwork_url character varying(255),
    jurisdiction_id uuid,
    explicit boolean DEFAULT false,
    lyrics text,
    plays_today integer DEFAULT 0,
    last_play_reset_date date DEFAULT CURRENT_DATE,
    isrc character varying(15),
    deleted_at timestamp without time zone
);


--
-- Name: COLUMN songs.explicit; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.songs.explicit IS 'Indicates if song contains explicit content';


--
-- Name: COLUMN songs.lyrics; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.songs.lyrics IS 'Full lyrics text for the song';


--
-- Name: COLUMN songs.plays_today; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.songs.plays_today IS 'Number of plays for current day, resets daily';


--
-- Name: COLUMN songs.last_play_reset_date; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.songs.last_play_reset_date IS 'Last date when plays_today was reset to 0';


--
-- Name: supporters; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supporters (
    supporter_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    listener_id uuid,
    artist_id uuid,
    created_at timestamp without time zone DEFAULT now()
);


--
-- Name: user_activity; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_activity (
    activity_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    user_id uuid NOT NULL,
    activity_type character varying(20) NOT NULL,
    page character varying(100),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT user_activity_activity_type_check CHECK (((activity_type)::text = ANY ((ARRAY['login'::character varying, 'page_view'::character varying, 'search'::character varying, 'vote'::character varying, 'comment'::character varying])::text[])))
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    user_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    username character varying(255) NOT NULL,
    email character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    role character varying(255) NOT NULL,
    supported_artist_id uuid,
    jurisdiction_id uuid,
    score integer DEFAULT 0,
    photo_url character varying(255),
    bio character varying(255),
    created_at timestamp without time zone DEFAULT now(),
    level character varying(255),
    genre_id uuid,
    default_song_id uuid,
    instagram_url character varying(255),
    twitter_url character varying(255),
    tiktok_url character varying(255),
    referral_code character varying(50) NOT NULL,
    total_plays integer DEFAULT 0 NOT NULL,
    total_votes integer DEFAULT 0 NOT NULL,
    deleted_at timestamp without time zone,
    CONSTRAINT check_instagram_url CHECK (((instagram_url IS NULL) OR ((instagram_url)::text ~ '^https?://'::text))),
    CONSTRAINT check_tiktok_url CHECK (((tiktok_url IS NULL) OR ((tiktok_url)::text ~ '^https?://'::text))),
    CONSTRAINT check_twitter_url CHECK (((twitter_url IS NULL) OR ((twitter_url)::text ~ '^https?://'::text))),
    CONSTRAINT users_role_check CHECK (((role)::text = ANY (ARRAY[('listener'::character varying)::text, ('artist'::character varying)::text])))
);


--
-- Name: video_plays; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.video_plays (
    play_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    video_id uuid,
    user_id uuid,
    played_at timestamp without time zone DEFAULT now(),
    duration_secs integer
);


--
-- Name: videos; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.videos (
    video_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    artist_id uuid,
    genre_id uuid,
    title character varying(255) NOT NULL,
    video_url character varying(255) NOT NULL,
    description character varying(255),
    duration integer,
    score integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT now(),
    level character varying(255),
    artwork_url character varying(255),
    jurisdiction_id uuid
);


--
-- Name: votes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.votes (
    vote_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    user_id uuid NOT NULL,
    target_type character varying(255) NOT NULL,
    target_id uuid NOT NULL,
    genre_id uuid,
    jurisdiction_id uuid,
    interval_id uuid,
    vote_date date NOT NULL,
    created_at timestamp without time zone DEFAULT now(),
    CONSTRAINT votes_target_type_check CHECK (((target_type)::text = ANY (ARRAY[('artist'::character varying)::text, ('song'::character varying)::text, ('video'::character varying)::text])))
);


--
-- Name: voting_intervals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.voting_intervals (
    interval_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    name character varying(255) NOT NULL,
    duration_days integer NOT NULL,
    created_at timestamp without time zone DEFAULT now()
);


--
-- Name: account_suspensions account_suspensions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_suspensions
    ADD CONSTRAINT account_suspensions_pkey PRIMARY KEY (suspension_id);


--
-- Name: ad_views ad_views_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ad_views
    ADD CONSTRAINT ad_views_pkey PRIMARY KEY (ad_view_id);


--
-- Name: admin_roles admin_roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_roles
    ADD CONSTRAINT admin_roles_pkey PRIMARY KEY (admin_role_id);


--
-- Name: awards awards_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.awards
    ADD CONSTRAINT awards_pkey PRIMARY KEY (award_id);


--
-- Name: comments comments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comments
    ADD CONSTRAINT comments_pkey PRIMARY KEY (comment_id);


--
-- Name: default_votes default_votes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.default_votes
    ADD CONSTRAINT default_votes_pkey PRIMARY KEY (default_vote_id);


--
-- Name: dmca_claims dmca_claims_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dmca_claims
    ADD CONSTRAINT dmca_claims_pkey PRIMARY KEY (claim_id);


--
-- Name: dmca_counter_notices dmca_counter_notices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dmca_counter_notices
    ADD CONSTRAINT dmca_counter_notices_pkey PRIMARY KEY (counter_notice_id);


--
-- Name: follows follows_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.follows
    ADD CONSTRAINT follows_pkey PRIMARY KEY (id);


--
-- Name: genres genres_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.genres
    ADD CONSTRAINT genres_pkey PRIMARY KEY (genre_id);


--
-- Name: jurisdictions jurisdictions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jurisdictions
    ADD CONSTRAINT jurisdictions_pkey PRIMARY KEY (jurisdiction_id);


--
-- Name: likes likes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.likes
    ADD CONSTRAINT likes_pkey PRIMARY KEY (like_id);


--
-- Name: likes likes_user_id_media_type_media_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.likes
    ADD CONSTRAINT likes_user_id_media_type_media_id_key UNIQUE (user_id, media_type, media_id);


--
-- Name: moderation_actions moderation_actions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.moderation_actions
    ADD CONSTRAINT moderation_actions_pkey PRIMARY KEY (action_id);


--
-- Name: password_reset_tokens password_reset_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_pkey PRIMARY KEY (token_id);


--
-- Name: playlist_items playlist_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.playlist_items
    ADD CONSTRAINT playlist_items_pkey PRIMARY KEY (playlist_item_id);


--
-- Name: playlist playlist_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.playlist
    ADD CONSTRAINT playlist_pkey PRIMARY KEY (playlist_id);


--
-- Name: referrals referrals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.referrals
    ADD CONSTRAINT referrals_pkey PRIMARY KEY (referral_id);


--
-- Name: referrals referrals_referrer_id_referred_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.referrals
    ADD CONSTRAINT referrals_referrer_id_referred_id_key UNIQUE (referrer_id, referred_id);


--
-- Name: song_plays song_plays_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.song_plays
    ADD CONSTRAINT song_plays_pkey PRIMARY KEY (play_id);


--
-- Name: songs songs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.songs
    ADD CONSTRAINT songs_pkey PRIMARY KEY (song_id);


--
-- Name: supporters supporters_listener_id_artist_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supporters
    ADD CONSTRAINT supporters_listener_id_artist_id_key UNIQUE (listener_id, artist_id);


--
-- Name: supporters supporters_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supporters
    ADD CONSTRAINT supporters_pkey PRIMARY KEY (supporter_id);


--
-- Name: awards ukhpn42l2ejoi3cor0smpp30cjl; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.awards
    ADD CONSTRAINT ukhpn42l2ejoi3cor0smpp30cjl UNIQUE (target_type, target_id, jurisdiction_id, interval_id, award_date);


--
-- Name: default_votes unique_default_vote; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.default_votes
    ADD CONSTRAINT unique_default_vote UNIQUE (user_id, target_type, genre_id, jurisdiction_id, interval_id);


--
-- Name: follows unique_follow; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.follows
    ADD CONSTRAINT unique_follow UNIQUE (follower_id, followed_id);


--
-- Name: admin_roles uq_admin_roles_user; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_roles
    ADD CONSTRAINT uq_admin_roles_user UNIQUE (user_id);


--
-- Name: password_reset_tokens uq_password_reset_token; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT uq_password_reset_token UNIQUE (token);


--
-- Name: user_activity user_activity_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_activity
    ADD CONSTRAINT user_activity_pkey PRIMARY KEY (activity_id);


--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (user_id);


--
-- Name: users users_referral_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_referral_code_key UNIQUE (referral_code);


--
-- Name: users users_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);


--
-- Name: video_plays video_plays_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_plays
    ADD CONSTRAINT video_plays_pkey PRIMARY KEY (play_id);


--
-- Name: videos videos_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.videos
    ADD CONSTRAINT videos_pkey PRIMARY KEY (video_id);


--
-- Name: votes votes_one_per_user_category_jurisdiction_day; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.votes
    ADD CONSTRAINT votes_one_per_user_category_jurisdiction_day UNIQUE (user_id, target_type, genre_id, jurisdiction_id, interval_id, vote_date);


--
-- Name: votes votes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.votes
    ADD CONSTRAINT votes_pkey PRIMARY KEY (vote_id);


--
-- Name: voting_intervals voting_intervals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.voting_intervals
    ADD CONSTRAINT voting_intervals_pkey PRIMARY KEY (interval_id);


--
-- Name: idx_account_suspensions_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_account_suspensions_active ON public.account_suspensions USING btree (user_id) WHERE (lifted_at IS NULL);


--
-- Name: idx_account_suspensions_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_account_suspensions_user ON public.account_suspensions USING btree (user_id);


--
-- Name: idx_ad_views_referred_artist_viewed_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ad_views_referred_artist_viewed_at ON public.ad_views USING btree (referred_artist_id, viewed_at);


--
-- Name: idx_ad_views_supported_artist_viewed_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ad_views_supported_artist_viewed_at ON public.ad_views USING btree (supported_artist_id, viewed_at);


--
-- Name: idx_admin_roles_role_level; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_admin_roles_role_level ON public.admin_roles USING btree (role_level);


--
-- Name: idx_admin_roles_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_admin_roles_user_id ON public.admin_roles USING btree (user_id);


--
-- Name: idx_awards_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_awards_lookup ON public.awards USING btree (jurisdiction_id, interval_id, award_date, target_type);


--
-- Name: idx_comments_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_comments_created_at ON public.comments USING btree (created_at DESC);


--
-- Name: idx_comments_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_comments_deleted_at ON public.comments USING btree (deleted_at);


--
-- Name: idx_comments_parent_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_comments_parent_id ON public.comments USING btree (parent_comment_id);


--
-- Name: idx_comments_song_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_comments_song_id ON public.comments USING btree (song_id);


--
-- Name: idx_comments_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_comments_user_id ON public.comments USING btree (user_id);


--
-- Name: idx_dmca_claims_assigned_to; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dmca_claims_assigned_to ON public.dmca_claims USING btree (assigned_to);


--
-- Name: idx_dmca_claims_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dmca_claims_created_at ON public.dmca_claims USING btree (created_at DESC);


--
-- Name: idx_dmca_claims_infringing_song; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dmca_claims_infringing_song ON public.dmca_claims USING btree (infringing_song_id);


--
-- Name: idx_dmca_claims_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dmca_claims_status ON public.dmca_claims USING btree (status);


--
-- Name: idx_dmca_counter_notices_claim; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dmca_counter_notices_claim ON public.dmca_counter_notices USING btree (claim_id);


--
-- Name: idx_dmca_counter_notices_respondent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dmca_counter_notices_respondent ON public.dmca_counter_notices USING btree (respondent_user_id);


--
-- Name: idx_jurisdictions_depth; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jurisdictions_depth ON public.jurisdictions USING btree (depth);


--
-- Name: idx_jurisdictions_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jurisdictions_parent ON public.jurisdictions USING btree (parent_jurisdiction_id);


--
-- Name: idx_jurisdictions_path; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jurisdictions_path ON public.jurisdictions USING btree (path);


--
-- Name: idx_jurisdictions_voting_enabled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_jurisdictions_voting_enabled ON public.jurisdictions USING btree (voting_enabled) WHERE (voting_enabled = true);


--
-- Name: idx_moderation_actions_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_moderation_actions_created_at ON public.moderation_actions USING btree (created_at DESC);


--
-- Name: idx_moderation_actions_performed_by; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_moderation_actions_performed_by ON public.moderation_actions USING btree (performed_by);


--
-- Name: idx_moderation_actions_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_moderation_actions_target ON public.moderation_actions USING btree (target_type, target_id);


--
-- Name: idx_moderation_actions_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_moderation_actions_type ON public.moderation_actions USING btree (action_type);


--
-- Name: idx_password_reset_tokens_token; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_password_reset_tokens_token ON public.password_reset_tokens USING btree (token);


--
-- Name: idx_password_reset_tokens_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_password_reset_tokens_user ON public.password_reset_tokens USING btree (user_id);


--
-- Name: idx_playlist_created_by; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_playlist_created_by ON public.playlist USING btree (created_by);


--
-- Name: idx_playlist_items_playlist; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_playlist_items_playlist ON public.playlist_items USING btree (playlist_id);


--
-- Name: idx_playlist_items_position; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_playlist_items_position ON public.playlist_items USING btree (playlist_id, "position");


--
-- Name: idx_playlist_items_song; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_playlist_items_song ON public.playlist_items USING btree (song_id);


--
-- Name: idx_song_plays_song_played_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_song_plays_song_played_at ON public.song_plays USING btree (song_id, played_at);


--
-- Name: idx_song_plays_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_song_plays_user_id ON public.song_plays USING btree (user_id);


--
-- Name: idx_songs_artist_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_songs_artist_id ON public.songs USING btree (artist_id);


--
-- Name: idx_songs_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_songs_deleted_at ON public.songs USING btree (deleted_at);


--
-- Name: idx_songs_isrc; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_songs_isrc ON public.songs USING btree (isrc) WHERE (isrc IS NOT NULL);


--
-- Name: idx_songs_jurisdiction_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_songs_jurisdiction_id ON public.songs USING btree (jurisdiction_id);


--
-- Name: idx_songs_last_play_reset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_songs_last_play_reset ON public.songs USING btree (last_play_reset_date);


--
-- Name: idx_songs_plays_today; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_songs_plays_today ON public.songs USING btree (plays_today DESC);


--
-- Name: idx_songs_score; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_songs_score ON public.songs USING btree (score DESC);


--
-- Name: idx_user_activity_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_activity_created ON public.user_activity USING btree (created_at);


--
-- Name: idx_user_activity_type_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_activity_type_created ON public.user_activity USING btree (activity_type, created_at);


--
-- Name: idx_user_activity_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_activity_user_created ON public.user_activity USING btree (user_id, created_at);


--
-- Name: idx_users_default_song_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_default_song_id ON public.users USING btree (default_song_id);


--
-- Name: idx_users_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_deleted_at ON public.users USING btree (deleted_at);


--
-- Name: idx_users_genre_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_genre_id ON public.users USING btree (genre_id);


--
-- Name: idx_users_jurisdiction_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_jurisdiction_id ON public.users USING btree (jurisdiction_id);


--
-- Name: idx_users_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_role ON public.users USING btree (role);


--
-- Name: idx_users_score; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_score ON public.users USING btree (score DESC);


--
-- Name: idx_users_supported_artist_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_supported_artist_id ON public.users USING btree (supported_artist_id);


--
-- Name: idx_video_plays_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_video_plays_user_id ON public.video_plays USING btree (user_id);


--
-- Name: idx_video_plays_video_played_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_video_plays_video_played_at ON public.video_plays USING btree (video_id, played_at);


--
-- Name: idx_videos_artist_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_videos_artist_id ON public.videos USING btree (artist_id);


--
-- Name: idx_videos_jurisdiction_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_videos_jurisdiction_id ON public.videos USING btree (jurisdiction_id);


--
-- Name: idx_videos_score; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_videos_score ON public.videos USING btree (score DESC);


--
-- Name: idx_votes_genre_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_votes_genre_id ON public.votes USING btree (genre_id);


--
-- Name: idx_votes_jurisdiction_interval_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_votes_jurisdiction_interval_date ON public.votes USING btree (jurisdiction_id, interval_id, vote_date);


--
-- Name: idx_votes_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_votes_target ON public.votes USING btree (target_type, target_id);


--
-- Name: idx_votes_user_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_votes_user_date ON public.votes USING btree (user_id, vote_date);


--
-- Name: users tr_user_jurisdiction_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER tr_user_jurisdiction_immutable BEFORE UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.prevent_user_jurisdiction_change();


--
-- Name: votes tr_votes_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER tr_votes_immutable BEFORE DELETE OR UPDATE ON public.votes FOR EACH ROW EXECUTE FUNCTION public.prevent_vote_modification();


--
-- Name: account_suspensions account_suspensions_lifted_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_suspensions
    ADD CONSTRAINT account_suspensions_lifted_by_fkey FOREIGN KEY (lifted_by) REFERENCES public.users(user_id);


--
-- Name: account_suspensions account_suspensions_suspended_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_suspensions
    ADD CONSTRAINT account_suspensions_suspended_by_fkey FOREIGN KEY (suspended_by) REFERENCES public.users(user_id);


--
-- Name: account_suspensions account_suspensions_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_suspensions
    ADD CONSTRAINT account_suspensions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: ad_views ad_views_artist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ad_views
    ADD CONSTRAINT ad_views_artist_id_fkey FOREIGN KEY (artist_id) REFERENCES public.users(user_id);


--
-- Name: ad_views ad_views_referred_artist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ad_views
    ADD CONSTRAINT ad_views_referred_artist_id_fkey FOREIGN KEY (referred_artist_id) REFERENCES public.users(user_id);


--
-- Name: ad_views ad_views_supported_artist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ad_views
    ADD CONSTRAINT ad_views_supported_artist_id_fkey FOREIGN KEY (supported_artist_id) REFERENCES public.users(user_id);


--
-- Name: ad_views ad_views_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ad_views
    ADD CONSTRAINT ad_views_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: admin_roles admin_roles_granted_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_roles
    ADD CONSTRAINT admin_roles_granted_by_fkey FOREIGN KEY (granted_by) REFERENCES public.users(user_id);


--
-- Name: admin_roles admin_roles_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_roles
    ADD CONSTRAINT admin_roles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: awards awards_genre_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.awards
    ADD CONSTRAINT awards_genre_id_fkey FOREIGN KEY (genre_id) REFERENCES public.genres(genre_id);


--
-- Name: awards awards_interval_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.awards
    ADD CONSTRAINT awards_interval_id_fkey FOREIGN KEY (interval_id) REFERENCES public.voting_intervals(interval_id);


--
-- Name: awards awards_jurisdiction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.awards
    ADD CONSTRAINT awards_jurisdiction_id_fkey FOREIGN KEY (jurisdiction_id) REFERENCES public.jurisdictions(jurisdiction_id);


--
-- Name: comments comments_parent_comment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comments
    ADD CONSTRAINT comments_parent_comment_id_fkey FOREIGN KEY (parent_comment_id) REFERENCES public.comments(comment_id) ON DELETE CASCADE;


--
-- Name: comments comments_song_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comments
    ADD CONSTRAINT comments_song_id_fkey FOREIGN KEY (song_id) REFERENCES public.songs(song_id) ON DELETE CASCADE;


--
-- Name: comments comments_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comments
    ADD CONSTRAINT comments_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: default_votes default_votes_genre_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.default_votes
    ADD CONSTRAINT default_votes_genre_id_fkey FOREIGN KEY (genre_id) REFERENCES public.genres(genre_id);


--
-- Name: default_votes default_votes_interval_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.default_votes
    ADD CONSTRAINT default_votes_interval_id_fkey FOREIGN KEY (interval_id) REFERENCES public.voting_intervals(interval_id);


--
-- Name: default_votes default_votes_jurisdiction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.default_votes
    ADD CONSTRAINT default_votes_jurisdiction_id_fkey FOREIGN KEY (jurisdiction_id) REFERENCES public.jurisdictions(jurisdiction_id);


--
-- Name: default_votes default_votes_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.default_votes
    ADD CONSTRAINT default_votes_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: dmca_claims dmca_claims_assigned_to_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dmca_claims
    ADD CONSTRAINT dmca_claims_assigned_to_fkey FOREIGN KEY (assigned_to) REFERENCES public.users(user_id);


--
-- Name: dmca_claims dmca_claims_infringing_song_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dmca_claims
    ADD CONSTRAINT dmca_claims_infringing_song_id_fkey FOREIGN KEY (infringing_song_id) REFERENCES public.songs(song_id);


--
-- Name: dmca_claims dmca_claims_resolved_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dmca_claims
    ADD CONSTRAINT dmca_claims_resolved_by_fkey FOREIGN KEY (resolved_by) REFERENCES public.users(user_id);


--
-- Name: dmca_counter_notices dmca_counter_notices_claim_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dmca_counter_notices
    ADD CONSTRAINT dmca_counter_notices_claim_id_fkey FOREIGN KEY (claim_id) REFERENCES public.dmca_claims(claim_id);


--
-- Name: dmca_counter_notices dmca_counter_notices_respondent_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dmca_counter_notices
    ADD CONSTRAINT dmca_counter_notices_respondent_user_id_fkey FOREIGN KEY (respondent_user_id) REFERENCES public.users(user_id);


--
-- Name: jurisdictions fk73kpo1srsqmw0ii49h8kplkpu; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jurisdictions
    ADD CONSTRAINT fk73kpo1srsqmw0ii49h8kplkpu FOREIGN KEY (parent_jurisdiction_id) REFERENCES public.jurisdictions(jurisdiction_id);


--
-- Name: follows fk_followed; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.follows
    ADD CONSTRAINT fk_followed FOREIGN KEY (followed_id) REFERENCES public.users(user_id);


--
-- Name: follows fk_follower; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.follows
    ADD CONSTRAINT fk_follower FOREIGN KEY (follower_id) REFERENCES public.users(user_id);


--
-- Name: playlist_items fk_playlist_items_playlist; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.playlist_items
    ADD CONSTRAINT fk_playlist_items_playlist FOREIGN KEY (playlist_id) REFERENCES public.playlist(playlist_id) ON DELETE CASCADE;


--
-- Name: playlist_items fk_playlist_items_song; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.playlist_items
    ADD CONSTRAINT fk_playlist_items_song FOREIGN KEY (song_id) REFERENCES public.songs(song_id) ON DELETE CASCADE;


--
-- Name: playlist fk_playlist_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.playlist
    ADD CONSTRAINT fk_playlist_user FOREIGN KEY (created_by) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: users fk_users_default_song; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT fk_users_default_song FOREIGN KEY (default_song_id) REFERENCES public.songs(song_id) ON DELETE SET NULL;


--
-- Name: users fk_users_genre; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT fk_users_genre FOREIGN KEY (genre_id) REFERENCES public.genres(genre_id);


--
-- Name: likes likes_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.likes
    ADD CONSTRAINT likes_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: moderation_actions moderation_actions_performed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.moderation_actions
    ADD CONSTRAINT moderation_actions_performed_by_fkey FOREIGN KEY (performed_by) REFERENCES public.users(user_id);


--
-- Name: password_reset_tokens password_reset_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: referrals referrals_referred_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.referrals
    ADD CONSTRAINT referrals_referred_id_fkey FOREIGN KEY (referred_id) REFERENCES public.users(user_id);


--
-- Name: referrals referrals_referrer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.referrals
    ADD CONSTRAINT referrals_referrer_id_fkey FOREIGN KEY (referrer_id) REFERENCES public.users(user_id);


--
-- Name: song_plays song_plays_song_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.song_plays
    ADD CONSTRAINT song_plays_song_id_fkey FOREIGN KEY (song_id) REFERENCES public.songs(song_id);


--
-- Name: song_plays song_plays_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.song_plays
    ADD CONSTRAINT song_plays_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: songs songs_artist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.songs
    ADD CONSTRAINT songs_artist_id_fkey FOREIGN KEY (artist_id) REFERENCES public.users(user_id);


--
-- Name: songs songs_genre_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.songs
    ADD CONSTRAINT songs_genre_id_fkey FOREIGN KEY (genre_id) REFERENCES public.genres(genre_id);


--
-- Name: songs songs_jurisdiction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.songs
    ADD CONSTRAINT songs_jurisdiction_id_fkey FOREIGN KEY (jurisdiction_id) REFERENCES public.jurisdictions(jurisdiction_id);


--
-- Name: supporters supporters_artist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supporters
    ADD CONSTRAINT supporters_artist_id_fkey FOREIGN KEY (artist_id) REFERENCES public.users(user_id);


--
-- Name: supporters supporters_listener_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supporters
    ADD CONSTRAINT supporters_listener_id_fkey FOREIGN KEY (listener_id) REFERENCES public.users(user_id);


--
-- Name: user_activity user_activity_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_activity
    ADD CONSTRAINT user_activity_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: users users_jurisdiction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_jurisdiction_id_fkey FOREIGN KEY (jurisdiction_id) REFERENCES public.jurisdictions(jurisdiction_id);


--
-- Name: video_plays video_plays_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_plays
    ADD CONSTRAINT video_plays_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: video_plays video_plays_video_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_plays
    ADD CONSTRAINT video_plays_video_id_fkey FOREIGN KEY (video_id) REFERENCES public.videos(video_id);


--
-- Name: videos videos_artist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.videos
    ADD CONSTRAINT videos_artist_id_fkey FOREIGN KEY (artist_id) REFERENCES public.users(user_id);


--
-- Name: videos videos_genre_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.videos
    ADD CONSTRAINT videos_genre_id_fkey FOREIGN KEY (genre_id) REFERENCES public.genres(genre_id);


--
-- Name: videos videos_jurisdiction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.videos
    ADD CONSTRAINT videos_jurisdiction_id_fkey FOREIGN KEY (jurisdiction_id) REFERENCES public.jurisdictions(jurisdiction_id);


--
-- Name: votes votes_genre_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.votes
    ADD CONSTRAINT votes_genre_id_fkey FOREIGN KEY (genre_id) REFERENCES public.genres(genre_id);


--
-- Name: votes votes_interval_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.votes
    ADD CONSTRAINT votes_interval_id_fkey FOREIGN KEY (interval_id) REFERENCES public.voting_intervals(interval_id);


--
-- Name: votes votes_jurisdiction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.votes
    ADD CONSTRAINT votes_jurisdiction_id_fkey FOREIGN KEY (jurisdiction_id) REFERENCES public.jurisdictions(jurisdiction_id);


--
-- Name: votes votes_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.votes
    ADD CONSTRAINT votes_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- PostgreSQL database dump complete
--

