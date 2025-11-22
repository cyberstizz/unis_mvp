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
-- Name: public; Type: SCHEMA; Schema: -; Owner: postgres
--

-- *not* creating schema, since initdb creates it


--
-- Name: uuid-ossp; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;


--
-- Name: EXTENSION "uuid-ossp"; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION "uuid-ossp" IS 'generate universally unique identifiers (UUIDs)';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: ad_views; Type: TABLE; Schema: public; Owner: unis_user
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


ALTER TABLE public.ad_views OWNER TO unis_user;

--
-- Name: awards; Type: TABLE; Schema: public; Owner: unis_user
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
    CONSTRAINT awards_target_type_check CHECK (((target_type)::text = ANY (ARRAY[('artist'::character varying)::text, ('song'::character varying)::text, ('video'::character varying)::text])))
);


ALTER TABLE public.awards OWNER TO unis_user;

--
-- Name: genres; Type: TABLE; Schema: public; Owner: unis_user
--

CREATE TABLE public.genres (
    genre_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    name character varying(255) NOT NULL,
    created_at timestamp without time zone DEFAULT now()
);


ALTER TABLE public.genres OWNER TO unis_user;

--
-- Name: jurisdictions; Type: TABLE; Schema: public; Owner: unis_user
--

CREATE TABLE public.jurisdictions (
    jurisdiction_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    name character varying(255) NOT NULL,
    polygon character varying(255),
    parent_jurisdiction_id uuid,
    bio character varying(255),
    created_at timestamp without time zone DEFAULT now(),
    symbol_url character varying(255)
);


ALTER TABLE public.jurisdictions OWNER TO unis_user;

--
-- Name: likes; Type: TABLE; Schema: public; Owner: unis_user
--

CREATE TABLE public.likes (
    like_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    media_type character varying(255),
    media_id uuid NOT NULL,
    user_id uuid,
    created_at timestamp without time zone DEFAULT now(),
    CONSTRAINT likes_media_type_check CHECK (((media_type)::text = ANY (ARRAY[('song'::character varying)::text, ('video'::character varying)::text])))
);


ALTER TABLE public.likes OWNER TO unis_user;

--
-- Name: referrals; Type: TABLE; Schema: public; Owner: unis_user
--

CREATE TABLE public.referrals (
    referral_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    referrer_id uuid,
    referred_id uuid,
    created_at timestamp without time zone DEFAULT now()
);


ALTER TABLE public.referrals OWNER TO unis_user;

--
-- Name: song_plays; Type: TABLE; Schema: public; Owner: unis_user
--

CREATE TABLE public.song_plays (
    play_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    song_id uuid,
    user_id uuid,
    played_at timestamp without time zone DEFAULT now(),
    duration_secs integer
);


ALTER TABLE public.song_plays OWNER TO unis_user;

--
-- Name: songs; Type: TABLE; Schema: public; Owner: unis_user
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
    jurisdiction_id uuid
);


ALTER TABLE public.songs OWNER TO unis_user;

--
-- Name: supporters; Type: TABLE; Schema: public; Owner: unis_user
--

CREATE TABLE public.supporters (
    supporter_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    listener_id uuid,
    artist_id uuid,
    created_at timestamp without time zone DEFAULT now()
);


ALTER TABLE public.supporters OWNER TO unis_user;

--
-- Name: users; Type: TABLE; Schema: public; Owner: unis_user
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
    CONSTRAINT users_role_check CHECK (((role)::text = ANY (ARRAY[('listener'::character varying)::text, ('artist'::character varying)::text])))
);


ALTER TABLE public.users OWNER TO unis_user;

--
-- Name: video_plays; Type: TABLE; Schema: public; Owner: unis_user
--

CREATE TABLE public.video_plays (
    play_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    video_id uuid,
    user_id uuid,
    played_at timestamp without time zone DEFAULT now(),
    duration_secs integer
);


ALTER TABLE public.video_plays OWNER TO unis_user;

--
-- Name: videos; Type: TABLE; Schema: public; Owner: unis_user
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


ALTER TABLE public.videos OWNER TO unis_user;

--
-- Name: votes; Type: TABLE; Schema: public; Owner: unis_user
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


ALTER TABLE public.votes OWNER TO unis_user;

--
-- Name: voting_intervals; Type: TABLE; Schema: public; Owner: unis_user
--

CREATE TABLE public.voting_intervals (
    interval_id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    name character varying(255) NOT NULL,
    duration_days integer NOT NULL,
    created_at timestamp without time zone DEFAULT now()
);


ALTER TABLE public.voting_intervals OWNER TO unis_user;

--
-- Name: ad_views ad_views_pkey; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.ad_views
    ADD CONSTRAINT ad_views_pkey PRIMARY KEY (ad_view_id);


--
-- Name: awards awards_pkey; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.awards
    ADD CONSTRAINT awards_pkey PRIMARY KEY (award_id);


--
-- Name: genres genres_pkey; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.genres
    ADD CONSTRAINT genres_pkey PRIMARY KEY (genre_id);


--
-- Name: jurisdictions jurisdictions_pkey; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.jurisdictions
    ADD CONSTRAINT jurisdictions_pkey PRIMARY KEY (jurisdiction_id);


--
-- Name: likes likes_pkey; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.likes
    ADD CONSTRAINT likes_pkey PRIMARY KEY (like_id);


--
-- Name: likes likes_user_id_media_type_media_id_key; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.likes
    ADD CONSTRAINT likes_user_id_media_type_media_id_key UNIQUE (user_id, media_type, media_id);


--
-- Name: referrals referrals_pkey; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.referrals
    ADD CONSTRAINT referrals_pkey PRIMARY KEY (referral_id);


--
-- Name: referrals referrals_referrer_id_referred_id_key; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.referrals
    ADD CONSTRAINT referrals_referrer_id_referred_id_key UNIQUE (referrer_id, referred_id);


--
-- Name: song_plays song_plays_pkey; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.song_plays
    ADD CONSTRAINT song_plays_pkey PRIMARY KEY (play_id);


--
-- Name: songs songs_pkey; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.songs
    ADD CONSTRAINT songs_pkey PRIMARY KEY (song_id);


--
-- Name: supporters supporters_listener_id_artist_id_key; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.supporters
    ADD CONSTRAINT supporters_listener_id_artist_id_key UNIQUE (listener_id, artist_id);


--
-- Name: supporters supporters_pkey; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.supporters
    ADD CONSTRAINT supporters_pkey PRIMARY KEY (supporter_id);


--
-- Name: awards ukhpn42l2ejoi3cor0smpp30cjl; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.awards
    ADD CONSTRAINT ukhpn42l2ejoi3cor0smpp30cjl UNIQUE (target_type, target_id, jurisdiction_id, interval_id, award_date);


--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (user_id);


--
-- Name: users users_username_key; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);


--
-- Name: video_plays video_plays_pkey; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.video_plays
    ADD CONSTRAINT video_plays_pkey PRIMARY KEY (play_id);


--
-- Name: videos videos_pkey; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.videos
    ADD CONSTRAINT videos_pkey PRIMARY KEY (video_id);


--
-- Name: votes votes_pkey; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.votes
    ADD CONSTRAINT votes_pkey PRIMARY KEY (vote_id);


--
-- Name: votes votes_user_id_target_type_genre_id_jurisdiction_id_interval_key; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.votes
    ADD CONSTRAINT votes_user_id_target_type_genre_id_jurisdiction_id_interval_key UNIQUE (user_id, target_type, genre_id, jurisdiction_id, interval_id, vote_date);


--
-- Name: voting_intervals voting_intervals_pkey; Type: CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.voting_intervals
    ADD CONSTRAINT voting_intervals_pkey PRIMARY KEY (interval_id);


--
-- Name: idx_users_default_song_id; Type: INDEX; Schema: public; Owner: unis_user
--

CREATE INDEX idx_users_default_song_id ON public.users USING btree (default_song_id);


--
-- Name: idx_users_genre_id; Type: INDEX; Schema: public; Owner: unis_user
--

CREATE INDEX idx_users_genre_id ON public.users USING btree (genre_id);


--
-- Name: ad_views ad_views_artist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.ad_views
    ADD CONSTRAINT ad_views_artist_id_fkey FOREIGN KEY (artist_id) REFERENCES public.users(user_id);


--
-- Name: ad_views ad_views_referred_artist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.ad_views
    ADD CONSTRAINT ad_views_referred_artist_id_fkey FOREIGN KEY (referred_artist_id) REFERENCES public.users(user_id);


--
-- Name: ad_views ad_views_supported_artist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.ad_views
    ADD CONSTRAINT ad_views_supported_artist_id_fkey FOREIGN KEY (supported_artist_id) REFERENCES public.users(user_id);


--
-- Name: ad_views ad_views_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.ad_views
    ADD CONSTRAINT ad_views_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: awards awards_genre_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.awards
    ADD CONSTRAINT awards_genre_id_fkey FOREIGN KEY (genre_id) REFERENCES public.genres(genre_id);


--
-- Name: awards awards_interval_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.awards
    ADD CONSTRAINT awards_interval_id_fkey FOREIGN KEY (interval_id) REFERENCES public.voting_intervals(interval_id);


--
-- Name: awards awards_jurisdiction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.awards
    ADD CONSTRAINT awards_jurisdiction_id_fkey FOREIGN KEY (jurisdiction_id) REFERENCES public.jurisdictions(jurisdiction_id);


--
-- Name: jurisdictions fk73kpo1srsqmw0ii49h8kplkpu; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.jurisdictions
    ADD CONSTRAINT fk73kpo1srsqmw0ii49h8kplkpu FOREIGN KEY (parent_jurisdiction_id) REFERENCES public.jurisdictions(jurisdiction_id);


--
-- Name: users fk_users_default_song; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT fk_users_default_song FOREIGN KEY (default_song_id) REFERENCES public.songs(song_id) ON DELETE SET NULL;


--
-- Name: users fk_users_genre; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT fk_users_genre FOREIGN KEY (genre_id) REFERENCES public.genres(genre_id);


--
-- Name: likes likes_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.likes
    ADD CONSTRAINT likes_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: referrals referrals_referred_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.referrals
    ADD CONSTRAINT referrals_referred_id_fkey FOREIGN KEY (referred_id) REFERENCES public.users(user_id);


--
-- Name: referrals referrals_referrer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.referrals
    ADD CONSTRAINT referrals_referrer_id_fkey FOREIGN KEY (referrer_id) REFERENCES public.users(user_id);


--
-- Name: song_plays song_plays_song_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.song_plays
    ADD CONSTRAINT song_plays_song_id_fkey FOREIGN KEY (song_id) REFERENCES public.songs(song_id);


--
-- Name: song_plays song_plays_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.song_plays
    ADD CONSTRAINT song_plays_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: songs songs_artist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.songs
    ADD CONSTRAINT songs_artist_id_fkey FOREIGN KEY (artist_id) REFERENCES public.users(user_id);


--
-- Name: songs songs_genre_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.songs
    ADD CONSTRAINT songs_genre_id_fkey FOREIGN KEY (genre_id) REFERENCES public.genres(genre_id);


--
-- Name: songs songs_jurisdiction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.songs
    ADD CONSTRAINT songs_jurisdiction_id_fkey FOREIGN KEY (jurisdiction_id) REFERENCES public.jurisdictions(jurisdiction_id);


--
-- Name: supporters supporters_artist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.supporters
    ADD CONSTRAINT supporters_artist_id_fkey FOREIGN KEY (artist_id) REFERENCES public.users(user_id);


--
-- Name: supporters supporters_listener_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.supporters
    ADD CONSTRAINT supporters_listener_id_fkey FOREIGN KEY (listener_id) REFERENCES public.users(user_id);


--
-- Name: users users_jurisdiction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_jurisdiction_id_fkey FOREIGN KEY (jurisdiction_id) REFERENCES public.jurisdictions(jurisdiction_id);


--
-- Name: video_plays video_plays_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.video_plays
    ADD CONSTRAINT video_plays_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: video_plays video_plays_video_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.video_plays
    ADD CONSTRAINT video_plays_video_id_fkey FOREIGN KEY (video_id) REFERENCES public.videos(video_id);


--
-- Name: videos videos_artist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.videos
    ADD CONSTRAINT videos_artist_id_fkey FOREIGN KEY (artist_id) REFERENCES public.users(user_id);


--
-- Name: videos videos_genre_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.videos
    ADD CONSTRAINT videos_genre_id_fkey FOREIGN KEY (genre_id) REFERENCES public.genres(genre_id);


--
-- Name: videos videos_jurisdiction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.videos
    ADD CONSTRAINT videos_jurisdiction_id_fkey FOREIGN KEY (jurisdiction_id) REFERENCES public.jurisdictions(jurisdiction_id);


--
-- Name: votes votes_genre_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.votes
    ADD CONSTRAINT votes_genre_id_fkey FOREIGN KEY (genre_id) REFERENCES public.genres(genre_id);


--
-- Name: votes votes_interval_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.votes
    ADD CONSTRAINT votes_interval_id_fkey FOREIGN KEY (interval_id) REFERENCES public.voting_intervals(interval_id);


--
-- Name: votes votes_jurisdiction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.votes
    ADD CONSTRAINT votes_jurisdiction_id_fkey FOREIGN KEY (jurisdiction_id) REFERENCES public.jurisdictions(jurisdiction_id);


--
-- Name: votes votes_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: unis_user
--

ALTER TABLE ONLY public.votes
    ADD CONSTRAINT votes_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: SCHEMA public; Type: ACL; Schema: -; Owner: postgres
--

GRANT ALL ON SCHEMA public TO unis_user;


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: public; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON SEQUENCES TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON SEQUENCES TO unis_user;


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: public; Owner: unis_user
--

ALTER DEFAULT PRIVILEGES FOR ROLE unis_user IN SCHEMA public GRANT ALL ON SEQUENCES TO unis_user;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: public; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON TABLES TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON TABLES TO unis_user;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: public; Owner: unis_user
--

ALTER DEFAULT PRIVILEGES FOR ROLE unis_user IN SCHEMA public GRANT ALL ON TABLES TO unis_user;
ALTER DEFAULT PRIVILEGES FOR ROLE unis_user IN SCHEMA public GRANT ALL ON TABLES TO postgres;


--
-- PostgreSQL database dump complete
--

