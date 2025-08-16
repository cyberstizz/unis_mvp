-- Enable UUID generation
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 2. jurisdictions (no dependencies)
CREATE TABLE jurisdictions (
    jurisdiction_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    polygon TEXT, -- Placeholder for PostGIS geometry
    parent_jurisdiction_id UUID, -- FK will be added after creation
    created_at TIMESTAMP DEFAULT NOW()
);

-- 3. genres (no dependencies)
CREATE TABLE genres (
    genre_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL
);

-- 5. voting_intervals (no dependencies)
CREATE TABLE voting_intervals (
    interval_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL,
    duration_days INT NOT NULL
);

-- 1. users (depends on jurisdictions)
CREATE TABLE users (
    user_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role VARCHAR(20) CHECK (role IN ('listener','artist')) NOT NULL,
    supported_artist_id UUID, -- FK will be added later
    jurisdiction_id UUID REFERENCES jurisdictions(jurisdiction_id),
    created_at TIMESTAMP DEFAULT NOW()
);

-- 4. songs (depends on users, genres)
CREATE TABLE songs (
    song_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    artist_id UUID NOT NULL REFERENCES users(user_id),
    title VARCHAR(255) NOT NULL,
    genre_id UUID REFERENCES genres(genre_id),
    file_url TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

-- 6. votes (depends on users, genres, jurisdictions, voting_intervals)
CREATE TABLE votes (
    vote_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(user_id),
    target_type VARCHAR(20) CHECK (target_type IN ('artist','song')) NOT NULL,
    target_id UUID NOT NULL,
    genre_id UUID REFERENCES genres(genre_id),
    jurisdiction_id UUID REFERENCES jurisdictions(jurisdiction_id),
    interval_id UUID REFERENCES voting_intervals(interval_id),
    vote_date DATE NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, target_type, genre_id, jurisdiction_id, interval_id, vote_date)
);

-- 7. awards (depends on genres, jurisdictions, voting_intervals)
CREATE TABLE awards (
    award_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    target_type VARCHAR(20) CHECK (target_type IN ('artist','song')) NOT NULL,
    target_id UUID NOT NULL,
    genre_id UUID REFERENCES genres(genre_id),
    jurisdiction_id UUID REFERENCES jurisdictions(jurisdiction_id),
    interval_id UUID REFERENCES voting_intervals(interval_id),
    award_date DATE NOT NULL,
    votes_count INT DEFAULT 0,
    engagement_score INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);

-- 8. ad_views (depends on users)
CREATE TABLE ad_views (
    ad_view_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(user_id),
    artist_id UUID REFERENCES users(user_id),
    ad_id UUID,
    viewed_at TIMESTAMP DEFAULT NOW(),
    duration_secs INT
);

-- 9. song_plays (depends on songs, users)
CREATE TABLE song_plays (
    play_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    song_id UUID REFERENCES songs(song_id),
    user_id UUID REFERENCES users(user_id),
    played_at TIMESTAMP DEFAULT NOW(),
    duration_secs INT
);

-- 10. referrals (depends on users)
CREATE TABLE referrals (
    referral_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    referrer_id UUID REFERENCES users(user_id),
    referred_id UUID REFERENCES users(user_id),
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(referrer_id, referred_id)
);

-- Add self-referencing FKs now that tables exist
ALTER TABLE jurisdictions
ADD CONSTRAINT fk_parent_jurisdiction
FOREIGN KEY (parent_jurisdiction_id) REFERENCES jurisdictions(jurisdiction_id);

ALTER TABLE users
ADD CONSTRAINT fk_supported_artist
FOREIGN KEY (supported_artist_id) REFERENCES users(user_id);

