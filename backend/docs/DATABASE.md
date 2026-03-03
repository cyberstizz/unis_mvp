# Unis Database Documentation

**Engine:** PostgreSQL (Supabase)  
**Extensions:** `uuid-ossp` (`uuid_generate_v4()` used as default PK on all tables)  
**Tables:** 19  
**Indexes:** 52  

> **How to use this file:** Attach alongside `ARCHITECTURE.md` when working on database migrations, query optimization, or any backend feature touching the data layer. For the ORM mapping of these tables, see the Entities section in `ARCHITECTURE.md`.

---

## Table of Contents

1. [Schema Overview](#1-schema-overview)
2. [Table Reference](#2-table-reference)
3. [Relationships](#4-relationships)
4. [Index Inventory](#4-index-inventory)
5. [Index Gap Analysis](#5-index-gap-analysis)
6. [Orphan Table — `default_votes`](#6-orphan-table--default_votes)
7. [Schema Notes & Findings](#7-schema-notes--findings)

---

## 1. Schema Overview

| Table | Purpose | Row Estimate |
|-------|---------|--------------|
| `users` | All platform users — listeners and artists | Core |
| `songs` | Audio media uploaded by artists | Core |
| `videos` | Video media uploaded by artists | Core |
| `votes` | User votes for songs or artists | High volume |
| `awards` | Computed winners per interval/jurisdiction/genre | Moderate |
| `song_plays` | Immutable play event log for songs | High volume |
| `video_plays` | Immutable play event log for videos | High volume |
| `likes` | Polymorphic likes on songs or videos | High volume |
| `comments` | Threaded comments on songs (soft-deleted) | Moderate |
| `follows` | Directional user-to-user follows | Moderate |
| `supporters` | Listener's active support of an artist | Low-Moderate |
| `referrals` | Registration referral chain | Low |
| `ad_views` | Ad view events for revenue attribution | High volume |
| `playlist` | User-owned playlists | Low-Moderate |
| `playlist_items` | Ordered tracks within a playlist | Low-Moderate |
| `jurisdictions` | Hierarchical geographic regions | Static |
| `genres` | Music genre lookup | Static |
| `voting_intervals` | Voting period definitions (daily/weekly/etc.) | Static |
| `default_votes` | ⚠️ No ORM entity — see Section 6 | Unknown |

---

## 2. Table Reference

---

### `users`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `user_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `username` | varchar(255) | NOT NULL | — | UNIQUE |
| `email` | varchar(255) | NOT NULL | — | UNIQUE |
| `password_hash` | varchar(255) | NOT NULL | — | |
| `role` | varchar(255) | NOT NULL | — | `'listener'` or `'artist'` |
| `jurisdiction_id` | uuid | YES | — | FOREIGN KEY → `jurisdictions` |
| `genre_id` | uuid | YES | — | FOREIGN KEY → `genres` |
| `default_song_id` | uuid | YES | — | FOREIGN KEY → `songs` |
| `supported_artist_id` | uuid | YES | — | No FK constraint |
| `referral_code` | varchar(50) | NOT NULL | — | UNIQUE |
| `score` | integer | YES | `0` | |
| `level` | varchar(255) | YES | — | `silver`/`gold`/`platinum`/`diamond` |
| `total_plays` | integer | NOT NULL | `0` | Denormalized counter |
| `total_votes` | integer | NOT NULL | `0` | Denormalized counter |
| `photo_url` | varchar(255) | YES | — | |
| `bio` | varchar(255) | YES | — | |
| `instagram_url` | varchar(255) | YES | — | |
| `twitter_url` | varchar(255) | YES | — | |
| `tiktok_url` | varchar(255) | YES | — | |
| `created_at` | timestamp | YES | `now()` | |
| `deleted_at` | timestamp | YES | — | Soft delete — null = active |

**Notes:**
- `supported_artist_id` references `users` but has **no FK constraint** — referential integrity not enforced at DB level
- `bio` and `photo_url` are capped at varchar(255) — bio may be too short for longer artist descriptions; consider `TEXT`
- `deleted_at` has an index (`idx_users_deleted_at`) but the application does not filter on it in auth queries — security gap (see `ARCHITECTURE.md` C5)

---

### `songs`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `song_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `artist_id` | uuid | NOT NULL | — | FOREIGN KEY → `users` |
| `title` | varchar(255) | NOT NULL | — | |
| `genre_id` | uuid | YES | — | FOREIGN KEY → `genres` |
| `jurisdiction_id` | uuid | YES | — | FOREIGN KEY → `jurisdictions` |
| `file_url` | varchar(255) | YES | — | Storage path (local or R2) |
| `artwork_url` | varchar(255) | YES | — | |
| `description` | varchar(255) | YES | — | |
| `duration` | integer | YES | — | In milliseconds |
| `score` | integer | NOT NULL | `0` | |
| `level` | varchar(255) | YES | — | `silver`/`gold`/`platinum` |
| `explicit` | boolean | YES | `false` | |
| `lyrics` | text | YES | — | |
| `plays_today` | integer | YES | `0` | Resets daily via scheduler |
| `last_play_reset_date` | date | YES | `CURRENT_DATE` | Tracks last daily reset |
| `created_at` | timestamp | YES | `now()` | |

**Notes:**
- `file_url` and `artwork_url` are varchar(255) — Cloudflare R2 URLs with long UUIDs may approach this limit; consider increasing to varchar(512) or TEXT
- `plays_today` reset is handled atomically in the play query, not by a separate UPDATE — `last_play_reset_date` is the guard
- No `deleted_at` — songs are hard-deleted; associated `song_plays`, `likes`, and `comments` become orphans without cascade deletes

---

### `videos`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `video_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `artist_id` | uuid | YES | — | FOREIGN KEY → `users` |
| `genre_id` | uuid | YES | — | FOREIGN KEY → `genres` |
| `jurisdiction_id` | uuid | YES | — | FOREIGN KEY → `jurisdictions` |
| `title` | varchar(255) | NOT NULL | — | |
| `video_url` | varchar(255) | NOT NULL | — | |
| `artwork_url` | varchar(255) | YES | — | |
| `description` | varchar(255) | YES | — | |
| `duration` | integer | YES | — | Units unconfirmed — align with `songs.duration` (ms) |
| `score` | integer | NOT NULL | `0` | |
| `level` | varchar(255) | YES | — | |
| `created_at` | timestamp | YES | `now()` | |

**Notes:**
- `artist_id` is nullable on `videos` but NOT NULL on `songs` — inconsistency; an orphaned video with no artist is possible
- No `plays_today` or `last_play_reset_date` — video trending uses a different mechanism than songs

---

### `votes`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `vote_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `user_id` | uuid | NOT NULL | — | FOREIGN KEY → `users`, part of unique constraint |
| `target_type` | varchar(255) | NOT NULL | — | `'song'` or `'artist'` |
| `target_id` | uuid | NOT NULL | — | No FK — polymorphic |
| `genre_id` | uuid | YES | — | FOREIGN KEY → `genres`, part of unique constraint |
| `jurisdiction_id` | uuid | YES | — | FOREIGN KEY → `jurisdictions`, part of unique constraint |
| `interval_id` | uuid | YES | — | FOREIGN KEY → `voting_intervals`, part of unique constraint |
| `vote_date` | date | NOT NULL | — | Part of unique constraint |
| `created_at` | timestamp | YES | `now()` | |

**Unique Constraint:** `votes_one_per_user_category_jurisdiction_day` on `(user_id, target_type, genre_id, jurisdiction_id, interval_id, vote_date)` — enforces one vote per category per day at the DB level. `target_id` intentionally excluded — user cannot switch their vote on the same day.

**Notes:**
- The unique constraint is the primary defense against duplicate votes — more reliable than service-layer checks alone
- `target_id` has no FK — referential integrity for voted-on songs/artists not enforced at DB level

---

### `awards`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `award_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `target_type` | varchar(255) | NOT NULL | — | Part of unique constraint |
| `target_id` | uuid | NOT NULL | — | No FK — polymorphic; part of unique constraint |
| `genre_id` | uuid | YES | — | FOREIGN KEY → `genres` |
| `jurisdiction_id` | uuid | YES | — | FOREIGN KEY → `jurisdictions`; part of unique constraint |
| `interval_id` | uuid | YES | — | FOREIGN KEY → `voting_intervals`; part of unique constraint |
| `award_date` | date | NOT NULL | — | Part of unique constraint |
| `votes_count` | integer | YES | `0` | |
| `engagement_score` | integer | YES | `0` | |
| `weight` | integer | YES | `100` | Award point weight |
| `determination_method` | varchar(20) | YES | — | `WEIGHTED_VOTES`, `PLAYS`, `LIKES`, `SCORE`, `SENIORITY`, `FALLBACK` |
| `winner_seniority` | timestamp | YES | — | Used for SENIORITY tiebreaker |
| `tied_candidates_count` | integer | YES | `0` | 0 = clear winner |
| `tiebreaker_details` | text | YES | — | JSON audit blob |
| `weighted_points` | integer | YES | `0` | |
| `plays_count` | integer | YES | `0` | |
| `likes_count` | integer | YES | `0` | |
| `caption` | varchar(255) | YES | — | Display caption |
| `created_at` | timestamp | YES | `now()` | |

**Unique Constraint:** `ukhpn42l2ejoi3cor0smpp30cjl` on `(target_type, target_id, jurisdiction_id, interval_id, award_date)` — prevents duplicate awards per category per date.

**Composite Index:** `idx_awards_lookup` on `(jurisdiction_id, interval_id, award_date, target_type)` — covers the most common leaderboard read pattern.

**Notes:**
- The auto-generated constraint name `ukhpn42l2ejoi3cor0smpp30cjl` is a Hibernate-generated hash — should be renamed to something readable like `uq_awards_category_date`
- `genre_id` is not part of the unique constraint — two awards for the same target/jurisdiction/interval/date but different genres are technically allowed

---

### `song_plays`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `play_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `song_id` | uuid | YES | — | FOREIGN KEY → `songs` |
| `user_id` | uuid | YES | — | FOREIGN KEY → `users` |
| `played_at` | timestamp | YES | `now()` | |
| `duration_secs` | integer | YES | — | Partial play detection |

**Notes:**
- Both `song_id` and `user_id` are nullable — anonymous plays are technically possible
- No composite index on `(song_id, played_at)` — this is the most queried combination for play counts and trending; **missing critical index**
- Table will grow unboundedly — consider partitioning by month at scale

---

### `video_plays`

Identical structure to `song_plays` with `video_id` instead of `song_id`. Same index gaps apply.

---

### `likes`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `like_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `media_type` | varchar(255) | YES | — | `'song'` or `'video'` |
| `media_id` | uuid | NOT NULL | — | No FK — polymorphic |
| `user_id` | uuid | YES | — | FOREIGN KEY → `users` |
| `created_at` | timestamp | YES | `now()` | |

**Unique Constraint:** `likes_user_id_media_type_media_id_key` on `(user_id, media_type, media_id)` — prevents duplicate likes at DB level.

**Notes:**
- `media_type` is nullable despite being required for correct deduplication — should be NOT NULL
- No FK on `media_id` — orphaned likes will remain if a song or video is deleted

---

### `comments`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `comment_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `song_id` | uuid | NOT NULL | — | FOREIGN KEY → `songs` |
| `user_id` | uuid | NOT NULL | — | FOREIGN KEY → `users` |
| `parent_comment_id` | uuid | YES | — | FOREIGN KEY → `comments` (self) |
| `content` | text | NOT NULL | — | |
| `created_at` | timestamp | YES | `now()` | |
| `updated_at` | timestamp | YES | `now()` | |
| `deleted_at` | timestamp | YES | — | Soft delete — null = active |

**Indexes:** `song_id`, `user_id`, `parent_comment_id`, `created_at DESC`, `deleted_at` — well-indexed.

**Notes:**
- Best-indexed table in the schema — all common query patterns are covered
- `deleted_at` index enables efficient filtering of soft-deleted records

---

### `follows`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `id` | uuid | NOT NULL | — | PRIMARY KEY (no default — must be set by app) |
| `follower_id` | uuid | NOT NULL | — | FOREIGN KEY → `users`; part of unique constraint |
| `followed_id` | uuid | NOT NULL | — | FOREIGN KEY → `users`; part of unique constraint |
| `created_at` | timestamp | YES | — | |

**Unique Constraint:** `unique_follow` on `(follower_id, followed_id)`.

**Notes:**
- `id` has no `uuid_generate_v4()` default — unlike every other table, the PK must be generated by the application. Inconsistency worth fixing.
- `created_at` has no default — will be NULL if the application doesn't explicitly set it

---

### `supporters`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `supporter_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `listener_id` | uuid | YES | — | FOREIGN KEY → `users`; part of unique constraint |
| `artist_id` | uuid | YES | — | FOREIGN KEY → `users`; part of unique constraint |
| `created_at` | timestamp | YES | `now()` | |

**Unique Constraint:** `supporters_listener_id_artist_id_key` on `(listener_id, artist_id)` — one support relationship per pair.

**Notes:**
- Both `listener_id` and `artist_id` are nullable — a supporter record with no listener or no artist is technically possible; both should be NOT NULL

---

### `referrals`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `referral_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `referrer_id` | uuid | YES | — | FOREIGN KEY → `users`; part of unique constraint |
| `referred_id` | uuid | YES | — | FOREIGN KEY → `users`; part of unique constraint |
| `created_at` | timestamp | YES | `now()` | |

**Unique Constraint:** `referrals_referrer_id_referred_id_key` on `(referrer_id, referred_id)`.

**Notes:**
- Both columns are nullable — same issue as `supporters`; should be NOT NULL
- No cascade delete — referral records for deleted users become orphans

---

### `ad_views`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `ad_view_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `user_id` | uuid | YES | — | FOREIGN KEY → `users` (viewer) |
| `artist_id` | uuid | YES | — | FOREIGN KEY → `users` (whose ad) |
| `ad_id` | uuid | YES | — | No FK — no `ads` table exists |
| `supported_artist_id` | uuid | YES | — | FOREIGN KEY → `users` |
| `referred_artist_id` | uuid | YES | — | FOREIGN KEY → `users` |
| `revenue_share` | numeric | YES | `0` | Calculated share |
| `duration_secs` | integer | YES | — | |
| `viewed_at` | timestamp | YES | `now()` | |

**Notes:**
- `ad_id` references a non-existent `ads` table — no FK constraint. If an `ads` table is added in future, this column is ready for it
- No indexes on `(supported_artist_id, viewed_at)` or `(referred_artist_id, viewed_at)` — these are the primary earnings query patterns; **missing critical indexes**
- All 4 user FK columns are nullable — an ad view with no viewer, no artist, no supported artist, and no referred artist is technically valid

---

### `playlist`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `playlist_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `name` | varchar(255) | NOT NULL | — | |
| `created_by` | uuid | NOT NULL | — | FOREIGN KEY → `users` |
| `created_at` | timestamp | YES | `now()` | |
| `updated_at` | timestamp | YES | `now()` | |

**Notes:**
- Table name is `playlist` (singular) while all other tables use plural — inconsistency. The ORM entity is `Playlist` and maps correctly but worth noting for raw SQL queries.

---

### `playlist_items`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `playlist_item_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `playlist_id` | uuid | NOT NULL | — | FOREIGN KEY → `playlist` |
| `song_id` | uuid | NOT NULL | — | FOREIGN KEY → `songs` |
| `position` | integer | NOT NULL | — | Track order |
| `added_at` | timestamp | YES | `now()` | |

**Indexes:** `(playlist_id)`, `(playlist_id, position)`, `(song_id)` — well-indexed for all access patterns.

---

### `jurisdictions`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `jurisdiction_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `name` | varchar(255) | NOT NULL | — | |
| `parent_jurisdiction_id` | uuid | YES | — | FOREIGN KEY → `jurisdictions` (self) |
| `depth` | integer | YES | — | Nesting level |
| `path` | text | YES | — | Materialized path e.g. `/unis/ny/harlem/downtown-harlem` |
| `polygon` | varchar(255) | YES | — | PostGIS placeholder — stored as string |
| `voting_enabled` | boolean | YES | `false` | |
| `symbol_url` | varchar(255) | YES | — | |
| `bio` | varchar(255) | YES | — | |
| `created_at` | timestamp | YES | `now()` | |

**Indexes:** `parent_jurisdiction_id`, `depth`, `path` (B-tree), `voting_enabled` (partial, WHERE true).

**Notes:**
- `path` index is B-tree, not GIN — B-tree supports prefix `LIKE` queries (`/unis/ny%`) but not arbitrary substring matches. For the current `LIKE` usage pattern (prefix-based path matching), B-tree is actually correct and sufficient.
- `polygon` is varchar(255) — PostGIS geometry data does not fit in varchar; this column is a placeholder. If spatial queries are needed, migrate to a PostGIS `geometry` column with a GIST index.
- `voting_enabled` defaults to `false` — new jurisdictions are opt-in for voting

---

### `genres`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `genre_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `name` | varchar(255) | NOT NULL | — | |
| `created_at` | timestamp | YES | `now()` | |

Static reference data. No additional indexes beyond PK.

---

### `voting_intervals`

| Column | Type | Nullable | Default | Constraint |
|--------|------|----------|---------|------------|
| `interval_id` | uuid | NOT NULL | `uuid_generate_v4()` | PRIMARY KEY |
| `name` | varchar(255) | NOT NULL | — | e.g. `Daily`, `Weekly`, `Monthly` |
| `duration_days` | integer | NOT NULL | — | |
| `created_at` | timestamp | YES | `now()` | |

Static reference data. No additional indexes beyond PK.

---

## 3. Relationships

```
users ──< songs          (artist_id)
users ──< videos         (artist_id)
users ──< votes          (user_id)
users ──< song_plays     (user_id)
users ──< video_plays    (user_id)
users ──< likes          (user_id)
users ──< comments       (user_id)
users ──< playlist       (created_by)
users ──< ad_views       (user_id, artist_id, supported_artist_id, referred_artist_id)
users ──< supporters     (listener_id, artist_id)
users ──< referrals      (referrer_id, referred_id)
users ──< follows        (follower_id, followed_id)
users >── users          (default_song_id → songs, supported_artist_id — no FK)

songs ──< song_plays     (song_id)
songs ──< likes          (media_id — no FK, polymorphic)
songs ──< comments       (song_id)
songs ──< playlist_items (song_id)

videos ──< video_plays   (video_id)
videos ──< likes         (media_id — no FK, polymorphic)

playlist ──< playlist_items (playlist_id)

genres >── songs         (genre_id)
genres >── videos        (genre_id)
genres >── votes         (genre_id)
genres >── awards        (genre_id)
genres >── users         (genre_id)

jurisdictions >── songs        (jurisdiction_id)
jurisdictions >── videos       (jurisdiction_id)
jurisdictions >── votes        (jurisdiction_id)
jurisdictions >── awards       (jurisdiction_id)
jurisdictions >── users        (jurisdiction_id)
jurisdictions >── jurisdictions (parent_jurisdiction_id — self-referencing)

voting_intervals >── votes  (interval_id)
voting_intervals >── awards (interval_id)
```

**Polymorphic relationships (no FK enforcement):**
- `votes.target_id` → song or artist UUID
- `awards.target_id` → song, artist, or video UUID
- `likes.media_id` → song or video UUID
- `ad_views.ad_id` → no ads table exists

---

## 4. Index Inventory

| Table | Index Name | Type | Columns | Purpose |
|-------|-----------|------|---------|---------|
| `ad_views` | `ad_views_pkey` | Unique B-tree | `ad_view_id` | PK |
| `awards` | `awards_pkey` | Unique B-tree | `award_id` | PK |
| `awards` | `idx_awards_lookup` | B-tree | `(jurisdiction_id, interval_id, award_date, target_type)` | Leaderboard read pattern |
| `awards` | `ukhpn42l2ejoi3cor0smpp30cjl` | Unique B-tree | `(target_type, target_id, jurisdiction_id, interval_id, award_date)` | Deduplication on compute |
| `comments` | `comments_pkey` | Unique B-tree | `comment_id` | PK |
| `comments` | `idx_comments_created_at` | B-tree DESC | `created_at` | Sort order |
| `comments` | `idx_comments_deleted_at` | B-tree | `deleted_at` | Soft delete filter |
| `comments` | `idx_comments_parent_id` | B-tree | `parent_comment_id` | Reply threading |
| `comments` | `idx_comments_song_id` | B-tree | `song_id` | Comments per song |
| `comments` | `idx_comments_user_id` | B-tree | `user_id` | Comments per user |
| `default_votes` | `default_votes_pkey` | Unique B-tree | `default_vote_id` | PK |
| `default_votes` | `unique_default_vote` | Unique B-tree | `(user_id, target_type, genre_id, jurisdiction_id, interval_id)` | Deduplication |
| `follows` | `follows_pkey` | Unique B-tree | `id` | PK |
| `follows` | `unique_follow` | Unique B-tree | `(follower_id, followed_id)` | Prevents duplicate follows |
| `genres` | `genres_pkey` | Unique B-tree | `genre_id` | PK |
| `jurisdictions` | `jurisdictions_pkey` | Unique B-tree | `jurisdiction_id` | PK |
| `jurisdictions` | `idx_jurisdictions_depth` | B-tree | `depth` | Tier queries |
| `jurisdictions` | `idx_jurisdictions_parent` | B-tree | `parent_jurisdiction_id` | Hierarchy traversal |
| `jurisdictions` | `idx_jurisdictions_path` | B-tree | `path` | Path prefix matching |
| `jurisdictions` | `idx_jurisdictions_voting_enabled` | Partial B-tree | `voting_enabled WHERE true` | Voting eligibility filter |
| `likes` | `likes_pkey` | Unique B-tree | `like_id` | PK |
| `likes` | `likes_user_id_media_type_media_id_key` | Unique B-tree | `(user_id, media_type, media_id)` | Deduplication |
| `playlist` | `playlist_pkey` | Unique B-tree | `playlist_id` | PK |
| `playlist` | `idx_playlist_created_by` | B-tree | `created_by` | User's playlists |
| `playlist_items` | `playlist_items_pkey` | Unique B-tree | `playlist_item_id` | PK |
| `playlist_items` | `idx_playlist_items_playlist` | B-tree | `playlist_id` | Tracks per playlist |
| `playlist_items` | `idx_playlist_items_position` | B-tree | `(playlist_id, position)` | Ordered track retrieval |
| `playlist_items` | `idx_playlist_items_song` | B-tree | `song_id` | Song membership check |
| `referrals` | `referrals_pkey` | Unique B-tree | `referral_id` | PK |
| `referrals` | `referrals_referrer_id_referred_id_key` | Unique B-tree | `(referrer_id, referred_id)` | Deduplication |
| `song_plays` | `song_plays_pkey` | Unique B-tree | `play_id` | PK |
| `songs` | `songs_pkey` | Unique B-tree | `song_id` | PK |
| `songs` | `idx_songs_last_play_reset` | B-tree | `last_play_reset_date` | Daily reset filter |
| `songs` | `idx_songs_plays_today` | B-tree DESC | `plays_today` | Today's trending sort |
| `supporters` | `supporters_pkey` | Unique B-tree | `supporter_id` | PK |
| `supporters` | `supporters_listener_id_artist_id_key` | Unique B-tree | `(listener_id, artist_id)` | One support per pair |
| `users` | `users_pkey` | Unique B-tree | `user_id` | PK |
| `users` | `users_email_key` | Unique B-tree | `email` | Login lookup |
| `users` | `users_username_key` | Unique B-tree | `username` | Login lookup |
| `users` | `users_referral_code_key` | Unique B-tree | `referral_code` | Registration check |
| `users` | `idx_users_referral_code` | B-tree | `referral_code` | Duplicate of above — redundant |
| `users` | `idx_users_deleted_at` | B-tree | `deleted_at` | Soft delete queries |
| `users` | `idx_users_default_song_id` | B-tree | `default_song_id` | Profile preview lookup |
| `users` | `idx_users_genre_id` | B-tree | `genre_id` | Genre filter |
| `video_plays` | `video_plays_pkey` | Unique B-tree | `play_id` | PK |
| `videos` | `videos_pkey` | Unique B-tree | `video_id` | PK |
| `votes` | `votes_pkey` | Unique B-tree | `vote_id` | PK |
| `votes` | `votes_one_per_user_category_jurisdiction_day` | Unique B-tree | `(user_id, target_type, genre_id, jurisdiction_id, interval_id, vote_date)` | One vote per category per day |
| `votes` | `idx_votes_jurisdiction_interval_date` | B-tree | `(jurisdiction_id, interval_id, vote_date)` | Leaderboard filter |
| `votes` | `idx_votes_target` | B-tree | `(target_type, target_id)` | Vote count per target |
| `votes` | `idx_votes_user_date` | B-tree | `(user_id, vote_date)` | User vote history |
| `voting_intervals` | `voting_intervals_pkey` | Unique B-tree | `interval_id` | PK |

**Total: 52 indexes across 19 tables.**

---

## 5. Index Gap Analysis

Comparing existing indexes against the query patterns in `ARCHITECTURE.md` Section 12.

| Table | Missing Index | Priority | Reason |
|-------|--------------|----------|--------|
| `songs` | `(artist_id)` | Critical | Most common song lookup — no index on FK |
| `songs` | `(jurisdiction_id)` | High | Hierarchy queries filter on this constantly |
| `songs` | `(score DESC)` | High | Leaderboard ORDER BY |
| `videos` | `(artist_id)` | Critical | Mirrors songs gap |
| `videos` | `(jurisdiction_id)` | High | Mirrors songs gap |
| `videos` | `(score DESC)` | High | Mirrors songs gap |
| `song_plays` | `(song_id, played_at)` | Critical | Every play count query — most queried combination |
| `song_plays` | `(user_id)` | Medium | Cascade delete and user activity queries |
| `video_plays` | `(video_id, played_at)` | Critical | Mirrors song_plays gap |
| `video_plays` | `(user_id)` | Medium | Mirrors song_plays gap |
| `ad_views` | `(supported_artist_id, viewed_at)` | High | Daily earnings sum queries |
| `ad_views` | `(referred_artist_id, viewed_at)` | Medium | Referral earnings queries |
| `users` | `(role)` | High | Frequent filter in score and leaderboard queries |
| `users` | `(jurisdiction_id)` | High | Hierarchy join — FK with no index |
| `users` | `(score DESC)` | High | Artist leaderboard ORDER BY |
| `users` | `(supported_artist_id)` | Medium | `countBySupportedArtistId` in score computation |
| `votes` | `(genre_id)` | Medium | Filter in genre-scoped leaderboard queries |

**Already covered (confirmed present):**
- `votes` unique constraint covers duplicate prevention ✅
- `awards` lookup index covers leaderboard reads ✅
- `likes` unique index covers deduplication ✅
- `jurisdictions` path index covers LIKE traversal ✅
- `comments` has the most complete index coverage of any table ✅

**Redundant index to remove:**
- `users.idx_users_referral_code` — duplicates `users_referral_code_key` (unique constraint). Two indexes on the same column serve no purpose; drop the non-unique one.

**Migration to add missing indexes:**
```sql
-- Critical
CREATE INDEX idx_songs_artist_id ON songs(artist_id);
CREATE INDEX idx_songs_jurisdiction_id ON songs(jurisdiction_id);
CREATE INDEX idx_songs_score ON songs(score DESC);
CREATE INDEX idx_videos_artist_id ON videos(artist_id);
CREATE INDEX idx_videos_jurisdiction_id ON videos(jurisdiction_id);
CREATE INDEX idx_videos_score ON videos(score DESC);
CREATE INDEX idx_song_plays_song_played_at ON song_plays(song_id, played_at);
CREATE INDEX idx_video_plays_video_played_at ON video_plays(video_id, played_at);

-- High
CREATE INDEX idx_ad_views_supported_artist_viewed_at ON ad_views(supported_artist_id, viewed_at);
CREATE INDEX idx_users_role ON users(role);
CREATE INDEX idx_users_jurisdiction_id ON users(jurisdiction_id);
CREATE INDEX idx_users_score ON users(score DESC);

-- Medium
CREATE INDEX idx_ad_views_referred_artist_viewed_at ON ad_views(referred_artist_id, viewed_at);
CREATE INDEX idx_song_plays_user_id ON song_plays(user_id);
CREATE INDEX idx_video_plays_user_id ON video_plays(user_id);
CREATE INDEX idx_users_supported_artist_id ON users(supported_artist_id);
CREATE INDEX idx_votes_genre_id ON votes(genre_id);

-- Cleanup
DROP INDEX idx_users_referral_code; -- redundant with users_referral_code_key
```

---

## 6. Orphan Table — `default_votes`

`default_votes` exists in the database but has **no corresponding Java entity, repository, or service** in the Spring Boot codebase. It was not encountered in any of the 4 documentation sessions.

**Structure:**

| Column | Type | Nullable | Notes |
|--------|------|----------|-------|
| `default_vote_id` | uuid | NOT NULL | PK |
| `user_id` | uuid | NOT NULL | FK → `users`; part of unique constraint |
| `target_type` | varchar(20) | NOT NULL | `CHECK` constraint: `'artist'` or `'song'` only |
| `target_id` | uuid | NOT NULL | No FK |
| `genre_id` | uuid | YES | FK → `genres`; part of unique constraint |
| `jurisdiction_id` | uuid | YES | FK → `jurisdictions`; part of unique constraint |
| `interval_id` | uuid | YES | FK → `voting_intervals`; part of unique constraint |
| `is_active` | boolean | YES | Default `true` |
| `created_at` | timestamp | YES | Default `now()` |
| `updated_at` | timestamp | YES | Default `now()` |

**Unique Constraint:** `unique_default_vote` on `(user_id, target_type, genre_id, jurisdiction_id, interval_id)`

**Interpretation:** This table appears designed to store a user's standing vote preference — a "default" vote that persists across intervals rather than being cast manually each day. The `is_active` flag suggests it was intended to be toggled on/off. The `CHECK` constraint on `target_type` shows it was carefully designed, not accidentally created.

**Likely intent:** Auto-submit a user's vote at the start of each interval without requiring manual action. This would be a significant UX feature — "set it and forget it" voting.

**Current status:** The table is fully structured with proper constraints and an index, but the feature was never implemented in the service layer. It contains no data or is unused.

**Decision required:** Either implement the feature (create `DefaultVote` entity + repository + service logic in the daily award cron) or drop the table to keep the schema clean. Do not leave it in an undocumented limbo state.

---

## 7. Schema Notes & Findings

These are issues found by comparing the live schema against the ORM entity layer documented in `ARCHITECTURE.md`.

| # | Finding | Table | Severity | Action |
|---|---------|-------|----------|--------|
| S1 | `song_plays` and `video_plays` missing composite index on `(entity_id, played_at)` | `song_plays`, `video_plays` | Critical | Add indexes — see migration in Section 5 |
| S2 | `songs` and `videos` missing index on `artist_id`, `jurisdiction_id`, `score` | `songs`, `videos` | Critical/High | Add indexes — see migration in Section 5 |
| S3 | `default_votes` table has no ORM entity or service implementation | `default_votes` | High | Implement or drop — see Section 6 |
| S4 | `follows.id` has no `uuid_generate_v4()` default — PK must be app-generated | `follows` | Medium | Add `ALTER TABLE follows ALTER COLUMN id SET DEFAULT uuid_generate_v4()` |
| S5 | `follows.created_at` has no default — will be NULL if app doesn't set it | `follows` | Low | Add `ALTER TABLE follows ALTER COLUMN created_at SET DEFAULT now()` |
| S6 | `supporters.listener_id` and `supporters.artist_id` are nullable | `supporters` | Medium | Both should be NOT NULL — a support record without both parties is invalid |
| S7 | `referrals.referrer_id` and `referrals.referred_id` are nullable | `referrals` | Medium | Both should be NOT NULL |
| S8 | `videos.artist_id` is nullable but `songs.artist_id` is NOT NULL | `videos` | Medium | Align — `artist_id` should be NOT NULL on `videos` |
| S9 | `likes.media_type` is nullable despite being required for deduplication | `likes` | Medium | Add NOT NULL constraint |
| S10 | `awards` unique constraint has Hibernate-generated hash name | `awards` | Low | Rename: `ALTER INDEX ukhpn42l2ejoi3cor0smpp30cjl RENAME TO uq_awards_category_date` |
| S11 | `idx_users_referral_code` is redundant with `users_referral_code_key` | `users` | Low | Drop redundant index |
| S12 | `ad_views` missing indexes on earnings query columns | `ad_views` | High | Add composite indexes — see migration in Section 5 |
| S13 | `polygon` column in `jurisdictions` is varchar(255) — too small for real geometry | `jurisdictions` | Low | Migrate to PostGIS `geometry` type when spatial queries are needed |
| S14 | `playlist` table is singular; all other tables are plural | `playlist` | Low | Cosmetic inconsistency — renaming would require ORM mapping update |
| S15 | `users.bio` is varchar(255) — may be too short for artist bios | `users` | Low | Consider `TEXT` type for unconstrained length |

---

*End of Database Documentation*  
*Cross-reference: `backend/docs/ARCHITECTURE.md` for ORM entity mappings and service-layer query documentation*