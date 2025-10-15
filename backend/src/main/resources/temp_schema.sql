-- Enable UUID generation (manual install)
-- CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Jurisdictions (no dependencies)
CREATE TABLE IF NOT EXISTS jurisdictions (
    jurisdiction_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    polygon TEXT, -- Placeholder for PostGIS geometry
    parent_jurisdiction_id UUID,
    bio TEXT,  -- Added for jurisdiction page
    created_at TIMESTAMP DEFAULT NOW()
);

-- Genres (no dependencies)
CREATE TABLE IF NOT EXISTS genres (
    genre_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()  -- Added here
);

-- Voting Intervals (no dependencies)
CREATE TABLE IF NOT EXISTS voting_intervals (
    interval_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL, -- Daily, Weekly, Monthly, Annual
    duration_days INT NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Users (depends on jurisdictions)
CREATE TABLE IF NOT EXISTS users (
    user_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role VARCHAR(20) CHECK (role IN ('listener','artist')) NOT NULL,
    supported_artist_id UUID,
    jurisdiction_id UUID REFERENCES jurisdictions(jurisdiction_id),
    score INT DEFAULT 0,  -- prestige score for both listeners & artists
    photo_url TEXT,  -- Added
    bio TEXT,  -- Added
    created_at TIMESTAMP DEFAULT NOW()
);

-- Songs (depends on users, genres)
CREATE TABLE IF NOT EXISTS songs (
    song_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    artist_id UUID NOT NULL REFERENCES users(user_id),
    title VARCHAR(255) NOT NULL,
    genre_id UUID REFERENCES genres(genre_id),
    file_url TEXT,
    score INT DEFAULT 0,  -- prestige score for songs
    description TEXT,  -- Added
    duration INTEGER,  -- Added
    created_at TIMESTAMP DEFAULT NOW()
);

-- Videos (depends on users, genres)
CREATE TABLE IF NOT EXISTS videos (
    video_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    artist_id UUID REFERENCES users(user_id),
    genre_id UUID REFERENCES genres(genre_id),
    title VARCHAR NOT NULL,
    video_url TEXT NOT NULL,  -- S3/CDN
    description TEXT,
    duration INTEGER,  -- seconds
    score INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Votes (depends on users, genres, jurisdictions, voting_intervals)
CREATE TABLE IF NOT EXISTS votes (
    vote_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(user_id),
    target_type VARCHAR(20) CHECK (target_type IN ('artist','song','video')) NOT NULL,  -- Added 'video'
    target_id UUID NOT NULL,
    genre_id UUID REFERENCES genres(genre_id),
    jurisdiction_id UUID REFERENCES jurisdictions(jurisdiction_id),
    interval_id UUID REFERENCES voting_intervals(interval_id),
    vote_date DATE NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, target_type, genre_id, jurisdiction_id, interval_id, vote_date)
);

-- Awards (depends on genres, jurisdictions, voting_intervals)
CREATE TABLE IF NOT EXISTS awards (
    award_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    target_type VARCHAR(20) CHECK (target_type IN ('artist','song','video')) NOT NULL,  -- Added 'video'
    target_id UUID NOT NULL,
    genre_id UUID REFERENCES genres(genre_id),
    jurisdiction_id UUID REFERENCES jurisdictions(jurisdiction_id),
    interval_id UUID REFERENCES voting_intervals(interval_id),
    award_date DATE NOT NULL,
    votes_count INT DEFAULT 0,
    engagement_score INT DEFAULT 0,
    weight INT DEFAULT 100,  -- scalable prestige weight for award type
    created_at TIMESTAMP DEFAULT NOW()
);

-- Ad Views (depends on users)
CREATE TABLE IF NOT EXISTS ad_views (
    ad_view_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(user_id),
    artist_id UUID REFERENCES users(user_id),
    ad_id UUID,
    supported_artist_id UUID REFERENCES users(user_id),  -- Added for shares
    referred_artist_id UUID REFERENCES users(user_id),   -- Added for shares
    revenue_share DECIMAL(10,4) DEFAULT 0,  -- Added
    viewed_at TIMESTAMP DEFAULT NOW(),
    duration_secs INT
);

-- Song Plays (depends on songs, users)
CREATE TABLE IF NOT EXISTS song_plays (
    play_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    song_id UUID REFERENCES songs(song_id),
    user_id UUID REFERENCES users(user_id),
    played_at TIMESTAMP DEFAULT NOW(),
    duration_secs INT
);

-- Video Plays (depends on videos, users)
CREATE TABLE IF NOT EXISTS video_plays (
    play_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    video_id UUID REFERENCES videos(video_id),
    user_id UUID REFERENCES users(user_id),
    played_at TIMESTAMP DEFAULT NOW(),
    duration_secs INT
);

-- Referrals (depends on users)
CREATE TABLE IF NOT EXISTS referrals (
    referral_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    referrer_id UUID REFERENCES users(user_id),
    referred_id UUID REFERENCES users(user_id),
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(referrer_id, referred_id)
);

-- Supporters (depends on users)
CREATE TABLE IF NOT EXISTS supporters (
    supporter_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    listener_id UUID REFERENCES users(user_id),
    artist_id UUID REFERENCES users(user_id),
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(listener_id, artist_id)
);

-- Likes (depends on users)
CREATE TABLE IF NOT EXISTS likes (
    like_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    media_type VARCHAR(20) CHECK (media_type IN ('song','video')),
    media_id UUID NOT NULL,
    user_id UUID REFERENCES users(user_id),
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, media_type, media_id)
);

-- Add self-referencing FKs now that tables exist
ALTER TABLE jurisdictions
ADD CONSTRAINT IF NOT EXISTS fk_parent_jurisdiction
FOREIGN KEY (parent_jurisdiction_id) REFERENCES jurisdictions(jurisdiction_id);

ALTER TABLE users
ADD CONSTRAINT IF NOT EXISTS fk_supported_artist
FOREIGN KEY (supported_artist_id) REFERENCES users(user_id);