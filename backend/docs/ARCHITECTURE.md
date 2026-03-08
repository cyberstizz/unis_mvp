# Unis Backend — Architecture Documentation

**Stack:** Spring Boot · PostgreSQL · Caffeine Cache · JWT (JJWT) · Lombok · JPA/Hibernate · Cloudflare R2  
**Port:** 8080 · **Profiles:** `local` (dev) · `prod` (Cloudflare R2)

> **How to use this file:** Attach at the start of any new AI thread working on the backend. Replaces the need to paste individual Java files for context. For the frontend equivalent, see `unis-web/docs/ARCHITECTURE.md`.

---

## Table of Contents

1. [Environment & Config Reference](#1-environment--config-reference)
2. [Backend Foundation & Config](#2-backend-foundation--config)
3. [Data Model — Entities](#3-data-model--entities)
4. [API Surface — Controllers](#4-api-surface--controllers)
5. [Data Transfer Objects](#5-data-transfer-objects)
6. [API Quick Reference](#6-api-quick-reference)
7. [Service Layer](#7-service-layer)
8. [Scheduled Tasks](#8-scheduled-tasks)
9. [Storage Architecture](#9-storage-architecture)
10. [Repository Layer](#10-repository-layer)
11. [Query Complexity Reference](#11-query-complexity-reference)
12. [Missing DB Indexes](#12-missing-db-indexes)
13. [Utility Classes](#13-utility-classes)
14. [Action Items](#14-action-items)

---

## 1. Environment & Config Reference

| Setting | Value | Notes |
|---------|-------|-------|
| **Port** | `8080` | |
| **Database** | PostgreSQL @ `localhost:5432/unis` | |
| **Storage (dev)** | Local filesystem — `uploads/` | `@Profile("local")` |
| **Storage (prod)** | Cloudflare R2 | `@Profile("prod")` |
| **Cache** | Caffeine (in-memory) | |
| **Cache TTL (default)** | 5 min | High-volatility caches use 1 min; awards use 10 min |
| **Cache max entries** | 1000 per cache | |
| **Cache names** | `songs`, `artists`, `jurisdictions`, `genres`, `userProfiles`, `awards`, `leaderboards`, `nominees`, `voteCounts`, `trending` | 10 registered; yml comment documents only 6 — align yml |
| **Email** | Resend API | `${RESEND_API_KEY}` |
| **JWT secret** | `${spring.jwt.secret}` | Injected from env — never hardcoded |
| **JWT expiry** | 86400000ms | 24 hours |
| **Max file upload** | 50MB | |
| **Scheduling pool** | 2 threads | Powers all `@Scheduled` tasks |
| **Prod frontend origin** | `https://unisprototypetwo.netlify.app` | Whitelisted in `SecurityConfig` only |
| **R2 config keys** | `cloudflare.r2.access-key/secret-key/endpoint/bucket-name/public-url` | All injected via env |

**Cache TTL Reference:**

| Cache Name | TTL | Rationale |
|------------|-----|-----------|
| `songs` | 5 min | Moderate change frequency |
| `artists` | 5 min | Moderate change frequency |
| `jurisdictions` | 5 min | Moderate change frequency |
| `genres` | 5 min | Moderate change frequency |
| `userProfiles` | 5 min | Moderate change frequency |
| `awards` | 10 min | Historical records, rarely change |
| `leaderboards` | 1 min | Near-live rankings |
| `nominees` | 1 min | Active voting page |
| `voteCounts` | 1 min | Displayed on voting cards |
| `trending` | 1 min | Changes constantly |

---

## 2. Backend Foundation & Config

### `UnisApplication.java`
**Purpose:** Application entry point. Bootstraps Spring Boot and activates scheduled task execution.

| Annotation | Effect |
|------------|--------|
| `@SpringBootApplication` | Enables component scan, auto-configuration, `@Configuration` |
| `@EnableScheduling` | Activates `@Scheduled` tasks; uses the 2-thread pool from yml |

---

### `config/CacheConfig.java`
**Purpose:** Configures all 10 Caffeine in-memory caches individually by data volatility via a private `buildCache(name, duration, unit)` factory.

**Refactor Flag:** `application.yml` documents only 6 cache names; implementation registers 10 — align the yml.

---

### `config/CorsConfig.java`
**Purpose:** Registers a global `CorsFilter` for dev origins. Credentials allowed. Applied to `/**`.

**Allowed Origins:** `localhost:5173`, `127.0.0.1:5173`, `localhost:3000`, `127.0.0.1:3000`, `192.168.*.*`

**Refactor Flags:**
- No production origin whitelisted here — prod origin is in `SecurityConfig` only.
- A second CORS config exists in `SecurityConfig.corsConfigurationSource()` — `CorsConfig.java` may be redundant and should be removed.

---

### `config/SecurityConfig.java`
**Purpose:** Defines the Spring Security filter chain. Stateless JWT, CSRF disabled, configures public vs authenticated vs admin-only endpoints.

**Key Beans:** `passwordEncoder()` (BCrypt), `authenticationManager()`, `corsConfigurationSource()` (includes Netlify prod origin)

**Filter Chain Rule Order (evaluated top to bottom):**

| Priority | Pattern | Access | Fix |
|----------|---------|--------|-----|
| 1 | `OPTIONS /**` | permitAll | Preflight |
| 2 | `/api/v1/admin/**` | `hasRole("ADMIN")` | C3 |
| 3 | `POST /api/v1/awards/recompute-all` | `hasRole("ADMIN")` | C2 |
| 4 | `POST /api/v1/awards/compute` | `hasRole("ADMIN")` | C2 |
| 5 | `GET /api/v1/awards/cron/manual` | `hasRole("ADMIN")` | C2 |
| 6 | `POST /api/v1/vote/awards/compute` | `hasRole("ADMIN")` | C2 |
| 7 | `POST /api/v1/media/song` | authenticated | C1 |
| 8 | `POST /api/v1/media/video` | authenticated | C6 |
| 9 | `DELETE /api/v1/media/song/**` | authenticated | C6 |
| 10 | `DELETE /api/v1/media/video/**` | authenticated | C6 |
| 11 | `PATCH /api/v1/media/song/**` | authenticated | C6 |
| 12 | `POST /api/v1/media/song/*/like` | authenticated | C6 |
| 13 | `DELETE /api/v1/media/song/*/like` | authenticated | C6 |
| 14 | `POST /api/v1/vote/submit` | authenticated | C6 |
| 15 | `POST /api/v1/comments` | authenticated | C6 |
| 16 | `PATCH /api/v1/comments/**` | authenticated | C6 |
| 17 | `DELETE /api/v1/comments/**` | authenticated | C6 |
| 18 | `PUT /api/v1/users/profile/*/photo` | authenticated | C4 |
| 19 | `PUT /api/v1/users/profile/*/bio` | authenticated | C4 |
| 20 | `PUT /api/v1/users/profile/*/password` | authenticated | C4 |
| 21 | `PUT /api/v1/users/profile/*` | authenticated | C4 |

**Public Endpoints (no token required):**

| Pattern | Notes |
|---------|-------|
| `OPTIONS /**` | Preflight |
| `/api/auth/**` | Auth flows |
| `/api/v1/users/register` | Registration |
| `/api/v1/users/login` | Login |
| `/api/v1/users/default-song` | Guest playback |
| `/api/v1/users/*/default-song` | Guest playback by userId |
| `/api/v1/users/artists/active` | Public browse |
| `GET /api/v1/users/profile` | Public browse (GET only) |
| `GET /api/v1/users/profile/photo` | Public browse (GET only) |
| `/api/v1/users/me` | ⚠️ Still likely unintentional — review |
| `/api/v1/users/check-email` | Registration helper |
| `/api/v1/users/check-username` | Registration helper |
| `/api/v1/users/validate-referral/**` | Registration helper |
| `/api/v1/jurisdictions/by-location` | Onboarding geo-lookup |
| `/uploads/**` | Static files (dev only) |
| `/actuator/**` | Ops |
| `/error/**` | Error pages |

**Note:** `hasRole("ADMIN")` checks for Spring Security authority `ROLE_ADMIN`. This is populated by `UserDetailsServiceImpl` from the `admin_roles` table (see Section 7).

---

### `config/JwtUtil.java`
**Purpose:** Stateless JWT utility. Generates, validates, and extracts claims from tokens.

**Token Payload:**

| Claim | Value |
|-------|-------|
| `sub` | User email |
| `userId` | User ID (String) |
| `role` | User role (String) |
| `iat` / `exp` | Issued at / expiry (24h) |

**Key Methods:** `generateToken(email, userId, role)`, `validateToken(token, userDetails)`, `validateToken(token)` (expiry-only overload), `extractUsername(token)`, `extractClaim(token, claimsResolver)` (public — used by JwtRequestFilter for userId extraction)

**Internal Methods:** `extractAllClaims(token)` (private — parses and verifies JWT), `isTokenExpired(token)`, `createToken(claims, subject)`

**Refactor Flag:** Uses deprecated JJWT setters — upgrade to JJWT 0.12+ builder pattern.

---

### `config/JwtRequestFilter.java`
**Purpose:** `OncePerRequestFilter`. Extracts Bearer token, validates via `JwtUtil`, populates `SecurityContext` with userId stored as credentials.

**Filter Flow:** Request → extract Bearer token → `extractUsername` → `loadUserByUsername` → `validateToken` → extract `userId` claim → set `SecurityContext` with userId as credentials → `chain.doFilter()`

**C6 Fix Applied:** The `UsernamePasswordAuthenticationToken` now stores the JWT's `userId` claim as its credentials field (was `null` before). This allows any downstream code to retrieve the authenticated userId via `SecurityUtils.getAuthenticatedUserId()` without re-parsing the token.

**Exception Handling:** Catches `IllegalArgumentException`, `ExpiredJwtException`, `MalformedJwtException`, and `SignatureException`.

**Key code:**
```java
String userId = jwtUtil.extractClaim(token, claims -> claims.get("userId", String.class));
UsernamePasswordAuthenticationToken authToken =
    new UsernamePasswordAuthenticationToken(userDetails, userId, userDetails.getAuthorities());
```

---

## 3. Data Model — Entities

### `User`
Central platform entity. Both `listener` and `artist` roles in a single table.

| Field | Type | Notes |
|-------|------|-------|
| `userId` | UUID | PK |
| `username` | String | Unique, non-null |
| `email` | String | Unique, non-null |
| `passwordHash` | String | BCrypt |
| `role` | `Role` enum | `listener` or `artist` |
| `jurisdiction` | `Jurisdiction` | `@ManyToOne` |
| `genre` | `Genre` | Artist's primary genre |
| `score` | Integer | Defaults 0 |
| `level` | String | `silver` / `gold` / `platinum` / `diamond` |
| `supportedArtistId` | UUID | Raw UUID — no `@ManyToOne` |
| `defaultSongId` | UUID | Raw UUID — no `@ManyToOne` |
| `defaultSong` | `@Transient Song` | Populated by service layer |
| `referralCode` | String | Unique, non-null, max 50 chars |
| `totalPlays` | Integer | Denormalized counter |
| `totalVotes` | Integer | Denormalized counter |
| `deletedAt` | LocalDateTime | Soft delete |
| `photoUrl`, `bio` | String | Profile fields |
| `instagramUrl`, `twitterUrl`, `tiktokUrl` | String | Social links |

**Refactor Flag:** `supportedArtistId` stored as raw UUID rather than `@ManyToOne` — inconsistent with other relationships.

---

### `Song`
Core audio media entity. Referenced by plays, likes, comments, votes, awards, and playlists.

| Field | Type | Notes |
|-------|------|-------|
| `songId` | UUID | PK |
| `title` | String | Non-null |
| `artist` | `User` | `@ManyToOne` (defaults EAGER) |
| `genre` | `Genre` | |
| `jurisdiction` | `Jurisdiction` | |
| `fileUrl` | String | Storage path |
| `artworkUrl` | String | |
| `duration` | Integer | In milliseconds |
| `score` | Integer | |
| `level` | String | `silver`/`gold`/`platinum` |
| `explicit` | Boolean | Defaults false |
| `lyrics` | TEXT | |
| `playsToday` | Integer | Resets daily |
| `lastPlayResetDate` | LocalDate | Tracks last reset |
| `playCount` | `@Transient Long` | Calculated at service layer |
| `likes` | `@Transient Integer` | Calculated at service layer |

**Refactor Flag:** `artist` has no explicit `FetchType` — JPA defaults EAGER for `@ManyToOne`, may cause unintended joins on bulk queries.

---

### `Video`
Mirrors `Song` structure for video content.

**Key Fields:** `videoId`, `artist` → `User`, `title`, `genre`, `jurisdiction`, `videoUrl`, `artworkUrl`, `score` (default 0), `level` (default `silver`), `description`, `duration`

**Refactor Flag:** `duration` unit not documented — `Song.duration` is explicitly milliseconds; confirm and align.

---

### `Vote`

| Field | Type | Notes |
|-------|------|-------|
| `voteId` | UUID | PK |
| `user` | `User` | Voter |
| `targetType` | String | `'song'` or `'artist'` — polymorphic |
| `targetId` | UUID | No FK |
| `genre` | `Genre` | |
| `jurisdiction` | `Jurisdiction` | |
| `interval` | `VotingInterval` | |
| `voteDate` | LocalDate | |

**Refactor Flag:** No unique constraint at entity level — duplicate vote prevention is service-layer only.

---

### `Award`
Persists computed winners. Full tiebreaker audit trail.

| Field | Type | Notes |
|-------|------|-------|
| `awardId` | UUID | PK |
| `targetType` | String | `'artist'`, `'song'`, or `'video'` |
| `targetId` | UUID | No FK |
| `genre` | `Genre` | |
| `jurisdiction` | `Jurisdiction` | |
| `interval` | `VotingInterval` | |
| `awardDate` | LocalDate | |
| `determinationMethod` | String | `WEIGHTED_VOTES`, `PLAYS`, `LIKES`, `SCORE`, `SENIORITY`, `FALLBACK` |
| `weightedPoints` | Integer | |
| `playsCount` | Integer | Tiebreaker #2 |
| `likesCount` | Integer | Tiebreaker #3 |
| `tiedCandidatesCount` | Integer | 0 = clear winner |
| `tiebreakerDetails` | TEXT (JSON) | Debug/audit blob |
| `song` / `user` | `@Transient` | Populated at service layer |

**Unique Constraint:** `(target_type, target_id, jurisdiction_id, interval_id, award_date)`

---

### `Jurisdiction`
Hierarchical geographic entity. Self-referencing with materialized `path` string for tree queries.

| Field | Type | Notes |
|-------|------|-------|
| `jurisdictionId` | UUID | PK |
| `name` | String | |
| `parentJurisdiction` | `Jurisdiction` | Self-referencing FK (nullable = root) |
| `depth` | Integer | Nesting level |
| `path` | String | e.g. `/unis/ny/harlem/downtown-harlem` |
| `polygon` | String | PostGIS placeholder — stored as String |
| `votingEnabled` | Boolean | Gates voting participation |
| `symbolUrl` | String | Flag/icon |
| `bio` | String | |

**Refactor Flag:** `polygon` is a plain String — upgrade to PostGIS `geometry` type for spatial queries.

---

### `Comment`
Threaded comments on songs. One level of nesting. Soft-deleted via `deleted_at`.

| Field | Type | Notes |
|-------|------|-------|
| `commentId` | UUID | PK |
| `song` | `Song` | `@JsonBackReference` |
| `user` | `User` | `FetchType.EAGER` |
| `parentComment` | `Comment` | Self-reference for replies |
| `replies` | `List<Comment>` | Ordered ASC, filtered by `deleted_at IS NULL` |
| `content` | TEXT | |
| `deletedAt` | LocalDateTime | Null = active |

**JPA Notes:** `@Where(clause = "deleted_at IS NULL")` at class level. `@JsonBackReference`/`@JsonManagedReference` prevent infinite serialization.

---

### `Playlist`
User-owned ordered song collection. `items` → `List<PlaylistTrack>` (`CascadeType.ALL`, `orphanRemoval = true`, ordered by `position ASC`)

---

### `PlaylistTrack`
Join entity between `Playlist` and `Song`. Maps to table `playlist_items` — class/table name diverge.

**Key Fields:** `playlist`, `song`, `position` (Integer), `addedAt`

---

### `SongPlay` / `VideoPlay`
Immutable play event logs. Fields: entity FK, `user`, `playedAt` (defaults now), `durationSecs`.

---

### `Like`
Polymorphic like on songs or videos. Fields: `likeId`, `mediaType` (`'song'`/`'video'`), `mediaId` (no FK), `user`, `createdAt`.

**Refactor Flag:** No unique constraint — deduplication is service-layer only.

---

### `Follow`
Directional follow. Fields: `follower` → `User`, `followed` → `User`, `createdAt`. **Unique:** `(follower_id, followed_id)`.

---

### `Supporter`
Listener's active support of an artist. Drives ad revenue routing. Fields: `listener` → `User`, `artist` → `User`, `createdAt`.

---

### `Referral`
Registration referral chain. Fields: `referrer` → `User`, `referred` → `User`, `createdAt`.

---

### `AdView`
Ad view event for revenue attribution. 4× `@ManyToOne` → `User` (viewer, artist, supportedArtist, referredArtist). Fields include `revenueShare` (BigDecimal), `durationSecs`, `viewedAt`.

---

### `Genre` / `VotingInterval`
Lookup/reference entities. `Genre`: `genreId`, `name`, `createdAt`. `VotingInterval`: `intervalId`, `name`, `durationDays`, `createdAt`.

---

## 4. API Surface — Controllers

### `AuthController.java`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/auth/login` | Public | Authenticate, returns JWT |
| POST | `/api/auth/logout` | Optional | Client-side stub — token not invalidated server-side |

**Refactor Flag:** Logout is a no-op. Path sits outside `/api/v1/` — intentional but inconsistent.

---

### `UserController.java`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/users/register` | Public | Register new user |
| GET | `/api/v1/users/profile/{userId}` | Public | Fetch full profile |
| PUT | `/api/v1/users/profile/{userId}/photo` | Required + Ownership (C4) | Update photo via URL |
| PUT | `/api/v1/users/profile/{userId}/bio` | Required + Ownership (C4) | Update bio |
| PUT | `/api/v1/users/profile/{userId}` | Required + Ownership (C4) | Update social URLs |
| PUT | `/api/v1/users/profile/{userId}/password` | Required + Ownership (C4) | Change password |
| GET | `/api/v1/users/artist/{artistId}` | Public | Artist profile |
| GET | `/api/v1/users/artist/top` | Public | Top N artists by jurisdiction |
| GET | `/api/v1/users/{userId}/default-song` | Public | Artist's pinned song |
| DELETE | `/api/v1/users/me` | Required (via Authentication) | Delete authenticated user |
| GET | `/api/v1/users/artists/active` | Public | All artists by score |
| PATCH | `/api/v1/users/profile/photo` | Public | Anon photo upload (temp signup) |
| PATCH | `/api/v1/users/profile` | Required (via Authentication) | Update photo/bio via multipart |
| PATCH | `/api/v1/users/default-song` | Required (via Authentication) | Set default song |
| GET | `/api/v1/users/referral-code/{userId}` | Public | Get referral code |
| GET | `/api/v1/users/validate-referral/{code}` | Public | Validate referral code |
| GET | `/api/v1/users/check-email` | Public | Email availability |
| GET | `/api/v1/users/check-username` | Public | Username availability |
| GET | `/api/v1/users/artists/with-preview` | Public | Artists with default song preview |
| GET | `/api/v1/users/{userId}/supporters/count` | Public | Supporter count |
| GET | `/api/v1/users/{userId}/followers/count` | Public | Follower count |
| POST | `/api/v1/users/{artistId}/follow` | Required (via Authentication) | Follow artist |
| DELETE | `/api/v1/users/{artistId}/follow` | Required (via Authentication) | Unfollow artist |
| GET | `/api/v1/users/{artistId}/is-following` | Required (via Authentication) | Check follow status |
| GET | `/api/v1/users/{userId}/total-plays` | Public | Total plays |
| GET | `/api/v1/users/{userId}/total-votes` | Public | Total vote score |
| GET | `/api/v1/users/{userId}/total-likes` | Public | Total likes |

**C4 Fix:** All PUT `/profile/{userId}/*` methods validate that `SecurityUtils.getAuthenticatedUserId()` matches the path `userId`. Returns 403 if mismatch.

**Remaining Refactor Flags:**
- Hardcoded `"UNIS-LAUNCH-2024"` bypass — remove before production (L17)
- N+1 in `getArtistsWithPreview` — still present
- `PATCH /profile/photo` temp endpoint — remove or secure before launch (L16)

---

### `MediaController.java`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/media/song` | Required (C1) | Upload song — artistId from JWT (C6) |
| POST | `/api/v1/media/video` | Required | Upload video |
| DELETE | `/api/v1/media/song/{songId}` | Required | Delete song |
| PATCH | `/api/v1/media/song/{songId}` | Required | Update song metadata |
| DELETE | `/api/v1/media/video/{videoId}` | Required | Delete video |
| POST | `/api/v1/media/song/{songId}/play` | Public | Record play — uses JWT userId if available, falls back to query param |
| POST | `/api/v1/media/video/{videoId}/play` | Public | Record video play — same fallback pattern |
| POST | `/api/v1/media/song/{songId}/like` | Required | Like song — userId from JWT (C6) |
| DELETE | `/api/v1/media/song/{songId}/like` | Required | Unlike song — userId from JWT (C6) |
| GET | `/api/v1/media/song/{songId}/is-liked` | Public | Check like status — uses JWT if available, falls back to query param |
| GET | `/api/v1/media/song/{songId}/likes/count` | Public | Like count |
| GET | `/api/v1/media/songs/jurisdiction/{jurisdictionId}` | Public | Top songs by jurisdiction |
| GET | `/api/v1/media/videos/jurisdiction/{jurisdictionId}` | Public | Top videos by jurisdiction |
| GET | `/api/v1/media/songs/artist/{artistId}` | Public | Artist's songs |
| GET | `/api/v1/media/videos/artist/{artistId}` | Public | Artist's videos |
| GET | `/api/v1/media/song/{songId}` | Public | Single song |
| GET | `/api/v1/media/song/{songId}/lyrics` | Public | Lyrics + explicit flag |
| GET | `/api/v1/media/trending` | Public | Mixed songs + videos by score |
| GET | `/api/v1/media/trending/today` | Public | Songs by `plays_today` |
| GET | `/api/v1/media/new` | Public | Newest songs by jurisdiction |
| PATCH | `/api/v1/media/song/{songId}/lyrics` | Required | Update lyrics only |

**C1 + C6 Fixes:** Song upload now requires authentication (was public). All mutation endpoints derive userId from JWT via `SecurityUtils.getAuthenticatedUserId()`. Play tracking and is-liked use JWT with fallback to query param for backward compatibility.

**Remaining Refactor Flags:** `SongWithStatsRowMapper` N+1 per row. `addVideo` and `deleteVideo` have no cache eviction.

---

### `VoteController.java`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/vote/submit` | Required | Submit vote — userId from JWT (C6) |
| GET | `/api/v1/vote/eligible-jurisdictions` | Required | Eligible jurisdictions — userId from JWT with query param fallback (C6) |
| GET | `/api/v1/vote/results` | Public | Filtered vote results |
| GET | `/api/v1/vote/total/{targetType}/{targetId}` | Public | Total votes for target |
| GET | `/api/v1/vote/votes/user/{userId}` | Public | Votes cast by user |
| GET | `/api/v1/vote/nominees` | Public | Top nominees |
| GET | `/api/v1/vote/check-eligibility` | Public | Eligibility check |
| GET | `/api/v1/vote/leaderboards` | Public | Ranked leaderboard |
| POST | `/api/v1/vote/awards/compute` | Admin only (C2) | Trigger award computation |
| GET | `/api/v1/vote/history` | Required (via Authentication) | Authenticated user's vote history |

**C6 Fix:** `submitVote` uses `SecurityUtils.getAuthenticatedUserId()` instead of `req.getUserId()`. `getEligibleJurisdictions` uses JWT with fallback.

**Note:** `getVoteHistory` already used `Authentication auth` parameter correctly — no change needed.

**Remaining Refactor Flag:** Duplicate `computeDailyAwards` — remove from `VoteService` (M1).

---

### `AwardController.java`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/awards/leaderboards` | Public | Live leaderboards |
| GET | `/api/v1/awards/past` | Public | Past awards with filters |
| GET | `/api/v1/awards/cron/manual` | Admin only (C2) | Manual daily trigger |
| POST | `/api/v1/awards/compute` | Admin only (C2) | Compute awards for params |
| POST | `/api/v1/awards/recompute-all` | Admin only (C2) | Wipe and recompute all history |
| GET | `/api/v1/awards/winner` | Public | Single winner for category/date |
| GET | `/api/v1/awards/artist/{artistId}` | Public | Paginated awards for artist |

**C2 Fix:** All destructive/compute endpoints now require `ROLE_ADMIN`.

---

### `JurisdictionController.java`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/jurisdictions/{jurisdictionId}` | Public | Jurisdiction details |
| GET | `/api/v1/jurisdictions/{jurisdictionId}/tops` | Public | Top 30 artists/songs |
| GET | `/api/v1/jurisdictions/byName/{name}` | Public | Find by name |
| GET | `/api/v1/jurisdictions/{jurisdictionId}/trending` | Public | Trending media |
| GET | `/api/v1/jurisdictions/{jurisdictionId}/children` | Public | Direct children |
| GET | `/api/v1/jurisdictions/{jurisdictionId}/children/detailed` | Public | Children with metadata |
| GET | `/api/v1/jurisdictions/roots` | Public | Root jurisdictions |
| GET | `/api/v1/jurisdictions/states` | Public | US states tier |
| GET | `/api/v1/jurisdictions/{jurisdictionId}/breadcrumb` | Public | Parent chain |
| GET | `/api/v1/jurisdictions/{jurisdictionId}/has-children` | Public | Drill-down check |
| GET | `/api/v1/jurisdictions/by-location` | Public | Jurisdiction for lat/lng |

---

### `PlaylistController.java`
> Maps to `/api/playlists` — missing `/v1/` segment. All endpoints require auth.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/playlists` | All playlists for user |
| POST | `/api/playlists` | Create playlist |
| GET | `/api/playlists/{playlistId}` | Single playlist with tracks |
| PUT | `/api/playlists/{playlistId}` | Rename playlist |
| DELETE | `/api/playlists/{playlistId}` | Delete playlist |
| POST | `/api/playlists/{playlistId}/tracks` | Add song |
| DELETE | `/api/playlists/{playlistId}/tracks/{playlistItemId}` | Remove track |
| PUT | `/api/playlists/{playlistId}/reorder` | Reorder tracks |

---

### `CommentController.java`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/comments` | Required | Create comment — userId from JWT (C6) |
| GET | `/api/v1/comments/song/{songId}` | Public | All comments for song |
| GET | `/api/v1/comments/song/{songId}/paginated` | Public | Paginated comments |
| GET | `/api/v1/comments/{commentId}` | Public | Single comment |
| GET | `/api/v1/comments/{commentId}/replies` | Public | Replies |
| GET | `/api/v1/comments/song/{songId}/count` | Public | Comment count |
| PATCH | `/api/v1/comments/{commentId}` | Required | Update comment — userId from JWT (C6) |
| DELETE | `/api/v1/comments/{commentId}` | Required | Delete comment — userId from JWT (C6) |

**C6 Fix:** All mutation endpoints use `SecurityUtils.getAuthenticatedUserId()` instead of client-supplied `userId` query parameter. The query param is kept as `required = false` for backward compatibility but is ignored by the backend.

---

### `EarningsController.java` / `FileController.java` / `AdminController.java`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/earnings/{artistId}` | Public* | Daily earnings stub |
| GET | `/api/v1/earnings/{artistId}/breakdown` | Public* | Earnings breakdown stub |
| GET | `/uploads/{filename}` | Public | Serve local file (dev only) |
| GET | `/api/v1/admin/cache/stats` | Admin only (C3) | Cache stats |
| DELETE | `/api/v1/admin/cache/clear` | Admin only (C3) | Clear all caches |
| DELETE | `/api/v1/admin/cache/clear/{cacheName}` | Admin only (C3) | Clear specific cache |
| GET | `/api/v1/admin/cache/names` | Admin only (C3) | List cache names |

**C3 Fix:** All `/api/v1/admin/**` endpoints now require `ROLE_ADMIN`.

---

## 5. Data Transfer Objects

| DTO | Used By | Purpose |
|-----|---------|---------|
| `LoginRequest` | `AuthController` | `email` + `password` |
| `AuthResponse` | `AuthController` | Returns `token` (JWT) |
| `RegisterRequest` | **Unused** | Dead code |
| `UserDto` | `UserController` | Multi-purpose catch-all — registration, photo, bio, password |
| `SongUploadRequest` | `MediaController` | Song metadata from JSON multipart |
| `VideoUploadRequest` | `MediaController` | Video metadata — mirrors song without `explicit`/`lyrics` |
| `VoteRequest` | `VoteController` | Vote submission — `userId` field present but ignored; backend uses JWT (C6) |
| `VoteHistoryDto` | `VoteController` | Vote history item with `nomineeName`, `nomineeImage`, `interval` |
| `LeaderboardDto` | `VoteController` | `rank`, `name`, `votes`, `artwork`, `artist`, `targetId` |
| `AwardDto` | **Unused in controller** | `AwardController` returns entity directly — should use this DTO |
| `CommentDTO` | `CommentController` | Container with 5 inner classes |
| `CreatePlaylistRequest` | `PlaylistController` | Single `name` field |
| `PlaylistDto` | `PlaylistController` | Full playlist with `List<TrackDto>` |
| `AddTrackRequest` | `PlaylistController` | Single `songId` field |

---

## 6. API Quick Reference

| Method | Path | Auth | Controller |
|--------|------|------|------------|
| POST | `/api/auth/login` | Public | Auth |
| POST | `/api/auth/logout` | Optional | Auth |
| POST | `/api/v1/users/register` | Public | User |
| GET | `/api/v1/users/profile/{userId}` | Public | User |
| PUT | `/api/v1/users/profile/{userId}/photo` | Required + Ownership (C4) | User |
| PUT | `/api/v1/users/profile/{userId}/bio` | Required + Ownership (C4) | User |
| PUT | `/api/v1/users/profile/{userId}` | Required + Ownership (C4) | User |
| PUT | `/api/v1/users/profile/{userId}/password` | Required + Ownership (C4) | User |
| GET | `/api/v1/users/artist/{artistId}` | Public | User |
| GET | `/api/v1/users/artist/top` | Public | User |
| GET | `/api/v1/users/{userId}/default-song` | Public | User |
| DELETE | `/api/v1/users/me` | Required | User |
| GET | `/api/v1/users/artists/active` | Public | User |
| PATCH | `/api/v1/users/profile/photo` | Public | User |
| PATCH | `/api/v1/users/profile` | Required | User |
| PATCH | `/api/v1/users/default-song` | Required | User |
| GET | `/api/v1/users/referral-code/{userId}` | Public | User |
| GET | `/api/v1/users/validate-referral/{code}` | Public | User |
| GET | `/api/v1/users/check-email` | Public | User |
| GET | `/api/v1/users/check-username` | Public | User |
| GET | `/api/v1/users/artists/with-preview` | Public | User |
| GET | `/api/v1/users/{userId}/supporters/count` | Public | User |
| GET | `/api/v1/users/{userId}/followers/count` | Public | User |
| POST | `/api/v1/users/{artistId}/follow` | Required | User |
| DELETE | `/api/v1/users/{artistId}/follow` | Required | User |
| GET | `/api/v1/users/{artistId}/is-following` | Required | User |
| GET | `/api/v1/users/{userId}/total-plays` | Public | User |
| GET | `/api/v1/users/{userId}/total-votes` | Public | User |
| GET | `/api/v1/users/{userId}/total-likes` | Public | User |
| POST | `/api/v1/media/song` | Required (C1) | Media |
| POST | `/api/v1/media/video` | Required | Media |
| DELETE | `/api/v1/media/song/{songId}` | Required | Media |
| PATCH | `/api/v1/media/song/{songId}` | Required | Media |
| DELETE | `/api/v1/media/video/{videoId}` | Required | Media |
| POST | `/api/v1/media/song/{songId}/play` | Public (JWT fallback) | Media |
| POST | `/api/v1/media/video/{videoId}/play` | Public (JWT fallback) | Media |
| POST | `/api/v1/media/song/{songId}/like` | Required (C6) | Media |
| DELETE | `/api/v1/media/song/{songId}/like` | Required (C6) | Media |
| GET | `/api/v1/media/song/{songId}/is-liked` | Public (JWT fallback) | Media |
| GET | `/api/v1/media/song/{songId}/likes/count` | Public | Media |
| GET | `/api/v1/media/songs/jurisdiction/{jurisdictionId}` | Public | Media |
| GET | `/api/v1/media/videos/jurisdiction/{jurisdictionId}` | Public | Media |
| GET | `/api/v1/media/songs/artist/{artistId}` | Public | Media |
| GET | `/api/v1/media/videos/artist/{artistId}` | Public | Media |
| GET | `/api/v1/media/song/{songId}` | Public | Media |
| GET | `/api/v1/media/song/{songId}/lyrics` | Public | Media |
| GET | `/api/v1/media/trending` | Public | Media |
| GET | `/api/v1/media/trending/today` | Public | Media |
| GET | `/api/v1/media/new` | Public | Media |
| PATCH | `/api/v1/media/song/{songId}/lyrics` | Required | Media |
| POST | `/api/v1/vote/submit` | Required (C6) | Vote |
| GET | `/api/v1/vote/eligible-jurisdictions` | Required (C6) | Vote |
| GET | `/api/v1/vote/results` | Public | Vote |
| GET | `/api/v1/vote/total/{targetType}/{targetId}` | Public | Vote |
| GET | `/api/v1/vote/votes/user/{userId}` | Public | Vote |
| GET | `/api/v1/vote/nominees` | Public | Vote |
| GET | `/api/v1/vote/check-eligibility` | Public | Vote |
| GET | `/api/v1/vote/leaderboards` | Public | Vote |
| POST | `/api/v1/vote/awards/compute` | Admin only (C2) | Vote |
| GET | `/api/v1/vote/history` | Required | Vote |
| GET | `/api/v1/awards/leaderboards` | Public | Award |
| GET | `/api/v1/awards/past` | Public | Award |
| GET | `/api/v1/awards/cron/manual` | Admin only (C2) | Award |
| POST | `/api/v1/awards/compute` | Admin only (C2) | Award |
| POST | `/api/v1/awards/recompute-all` | Admin only (C2) | Award |
| GET | `/api/v1/awards/winner` | Public | Award |
| GET | `/api/v1/awards/artist/{artistId}` | Public | Award |
| GET | `/api/v1/jurisdictions/{jurisdictionId}` | Public | Jurisdiction |
| GET | `/api/v1/jurisdictions/{jurisdictionId}/tops` | Public | Jurisdiction |
| GET | `/api/v1/jurisdictions/byName/{name}` | Public | Jurisdiction |
| GET | `/api/v1/jurisdictions/{jurisdictionId}/trending` | Public | Jurisdiction |
| GET | `/api/v1/jurisdictions/{jurisdictionId}/children` | Public | Jurisdiction |
| GET | `/api/v1/jurisdictions/{jurisdictionId}/children/detailed` | Public | Jurisdiction |
| GET | `/api/v1/jurisdictions/roots` | Public | Jurisdiction |
| GET | `/api/v1/jurisdictions/states` | Public | Jurisdiction |
| GET | `/api/v1/jurisdictions/{jurisdictionId}/breadcrumb` | Public | Jurisdiction |
| GET | `/api/v1/jurisdictions/{jurisdictionId}/has-children` | Public | Jurisdiction |
| GET | `/api/v1/jurisdictions/by-location` | Public | Jurisdiction |
| GET | `/api/playlists` | Required | Playlist |
| POST | `/api/playlists` | Required | Playlist |
| GET | `/api/playlists/{playlistId}` | Required | Playlist |
| PUT | `/api/playlists/{playlistId}` | Required | Playlist |
| DELETE | `/api/playlists/{playlistId}` | Required | Playlist |
| POST | `/api/playlists/{playlistId}/tracks` | Required | Playlist |
| DELETE | `/api/playlists/{playlistId}/tracks/{playlistItemId}` | Required | Playlist |
| PUT | `/api/playlists/{playlistId}/reorder` | Required | Playlist |
| POST | `/api/v1/comments` | Required (C6) | Comment |
| GET | `/api/v1/comments/song/{songId}` | Public | Comment |
| GET | `/api/v1/comments/song/{songId}/paginated` | Public | Comment |
| GET | `/api/v1/comments/{commentId}` | Public | Comment |
| GET | `/api/v1/comments/{commentId}/replies` | Public | Comment |
| GET | `/api/v1/comments/song/{songId}/count` | Public | Comment |
| PATCH | `/api/v1/comments/{commentId}` | Required (C6) | Comment |
| DELETE | `/api/v1/comments/{commentId}` | Required (C6) | Comment |
| GET | `/api/v1/earnings/{artistId}` | Public* | Earnings |
| GET | `/api/v1/earnings/{artistId}/breakdown` | Public* | Earnings |
| GET | `/uploads/{filename}` | Public | File |
| GET | `/api/v1/admin/cache/stats` | Admin only (C3) | Admin |
| DELETE | `/api/v1/admin/cache/clear` | Admin only (C3) | Admin |
| DELETE | `/api/v1/admin/cache/clear/{cacheName}` | Admin only (C3) | Admin |
| GET | `/api/v1/admin/cache/names` | Admin only (C3) | Admin |

---

## 7. Service Layer

### `AwardService.java`
**Purpose:** Computes, persists, and serves awards across all voting intervals, jurisdictions, and genres using weighted vote aggregation and a full tiebreaker cascade.

**Tiebreaker Cascade:** `WEIGHTED_VOTES` → `PLAYS` → `LIKES` → `SCORE` → `SENIORITY` → `FALLBACK`

**Vote Weight Values:** Annual=250, Midterm=200, Quarterly=60, Monthly=25, Weekly=20, Daily=10

**Award Point Values (added to winner score):** Annual=5000, Midterm=2500, Quarterly=500, Monthly=250, Weekly=100, Daily=50

**Key Methods:**

| Method | Logic |
|--------|-------|
| `computeAwardsInternal(...)` | Core loop: all voting-enabled jurisdictions × all genres → `computeSingleWinnerAward` for song and artist |
| `getCandidatesWithWeightedVotes(...)` | Native SQL. Bidirectional jurisdiction aggregation with interval weights. |
| `getCandidatesByEngagement(...)` | Zero-vote fallback. Top 10 by plays → likes → score → seniority. |
| `determineWinner(candidates)` | Walks tiebreaker cascade. Records method and tied count. |
| `computeAndSaveAwardsInNewTransaction(...)` | `REQUIRES_NEW` — exists because `getPastAwards` is `readOnly=true`. Self-injected via `@Lazy`. |
| `createFallbackAwards(...)` | Display-only Award objects. **Never persisted.** |
| `recomputeAllHistoricalAwards()` | Admin method. Recomputes Daily awards only for all distinct vote dates. |

**Business Logic:**
- Jurisdiction aggregation bidirectional — votes from target + all descendants + ancestors; winners must reside in target or descendants only
- Award deduplication: `existsAwardForCategory` checked before any computation
- `autoPopulateAwards` flag (default `true`) gates on-demand computation from read requests

**Refactor Flags:** Extensive `System.out.println` — replace with SLF4J. `createFallbackAwards` calls `findAll()` — dangerous at scale.

---

### `VoteService.java`
**Purpose:** Vote submission with jurisdiction eligibility enforcement, duplicate prevention, leaderboard and nominee queries.

**Key Business Logic:**
- One vote per `(userId, targetType, genreId, jurisdictionId, intervalId, voteDate)` — `targetId` excluded so user cannot switch vote on same day
- Eligibility relies on materialized `path` string — missing path throws exception
- `getIntervalStartDate` for Daily returns yesterday — testing convenience, must revert before production
- Leaderboard composite score = `COUNT(votes) + COUNT(plays)` — equal weight

**Refactor Flag:** Duplicate `computeDailyAwards` — remove from `VoteService`. Leaderboard fallback appends without deduplication.

---

### `UserService.java`
**Purpose:** User registration, profile reads/updates, supporter relationships, soft deletion, aggregate stats.

**Key Business Logic:**
- Referral points: Listeners earn +5; artists earn +2
- Artist cannot support themselves during registration
- Soft delete preserves votes and awards for historical integrity — confirmed intentional
- `getTopArtistsByJurisdiction` returns partial entities — `userId`, `username`, `defaultSongId` only

**C4 Fix Note:** Ownership is now validated at the controller layer via `SecurityUtils.getAuthenticatedUserId()` — controller rejects mismatched userId before calling service methods.

---

### `MediaService.java`
**Purpose:** Upload, storage, retrieval, deletion, play tracking, likes, and discovery feeds.

**Key Business Logic:**
- Jurisdiction fallback on upload: explicit `jurisdictionId` → artist's home jurisdiction → hardcoded default UUID
- `plays_today` reset is atomic in-query — sets to 1 (not increment) if `last_play_reset_date < today`
- Unlike does not deduct points — intentional; points represent historical engagement
- Duration computed via Apache Tika — falls back silently to 180s (3 min) if detection fails

**C6 Fix Applied:** `addSong()` now derives `artistId` from `SecurityUtils.getAuthenticatedUserId()` instead of the client-supplied value in the JSON metadata. The `req.getArtistId()` field is ignored.

**Remaining Refactor Flags:** `SongWithStatsRowMapper` N+1 per row. `addVideo` and `deleteVideo` have no cache eviction.

---

### `PlaylistService.java`
**Purpose:** CRUD for playlists and tracks including ordering and position reindexing.

**Business Logic:** Ownership enforced in every write — throws `RuntimeException("Unauthorized")`. `reorderPlaylist` silently drops tracks not in supplied ID list — data loss risk.

---

### `JurisdictionService.java`
**Purpose:** Jurisdiction lookups, hierarchy navigation, geographic point resolution, map metadata.

**Business Logic:** Active jurisdictions hardcoded as `["Harlem", "Uptown Harlem", "Downtown Harlem"]` — must be data-driven before national expansion.

---

### `CommentService.java`
**Purpose:** Comment creation, retrieval, update, soft deletion with single-level reply threading.

**Business Logic:** Max nesting depth = 1. Song artist can delete any comment on their songs. No rate limiting on creation.

---

### `EarningsService.java`
**Purpose:** Artist ad revenue from `AdView` records. CPM hardcoded at $0.01. Revenue split: supporter 50%, referral 10%. 70/30 adRevenue/impressions breakdown is hardcoded placeholder.

---

### `ScoreUpdateService.java`
**Purpose:** Real-time event-driven score updates.

**Score Events:**

| Event | Effect |
|-------|--------|
| `onPlay` | Listener +1, song +1, artist +1 |
| `onVote` (artist) | Voter +2, artist +3 |
| `onVote` (song) | Voter +2, song +3, artist +3 |
| `onLike` | Liker +1, song +2, artist +1 |
| `onSupporterAdded` | Artist +5 |
| `onReferral` (listener) | Referrer +5 |
| `onReferral` (artist) | Referrer +2 |
| `onAward` | Winner += award weight (50–5000) |

**Level Thresholds:** silver=0–99, gold=100–499, platinum=500–999, diamond=1000+

---

### `FileStorageService.java` (interface)
Defines `storeFile(MultipartFile) → String` and `deleteFile(String)`. Strategy pattern — profile selects implementation at startup.

**Refactor Flag:** `deleteFile` never called anywhere — orphaned files accumulate in both local and R2 storage.

---

### `LocalFileStorageService.java` (`@Profile("local")`)
Stores to `./uploads/`. Returns `/uploads/{UUID}-{timestamp}.{ext}`.

---

### `CloudflareR2Service.java` (`@Profile("prod")`)
AWS S3 SDK v2 with R2 endpoint override. `@PostConstruct initializeS3Client()`. All files under `uploads/` prefix. Uses `Region.of("auto")`.

**Refactor Flag:** Uses `javax.annotation.PostConstruct` — Spring Boot 3.x requires `jakarta.*`.

---

### `UserDetailsServiceImpl.java`
Loads `UserDetails` by email for JWT filter. Empty authorities list — role enforcement via JWT claims and `SecurityConfig` only.

**C5 Fix Applied:** Now calls `userRepository.findActiveByEmail(email)` instead of `findByEmail(email)`. The `findActiveByEmail` method filters `WHERE deleted_at IS NULL`, preventing soft-deleted users from authenticating.

**Auth Flow:** `JwtRequestFilter` → `loadUserByUsername(email)` → `findActiveByEmail(email)` → if user not found (including soft-deleted) → `UsernameNotFoundException` → 401 → frontend clears token and redirects to login.

---

## 8. Scheduled Tasks

All tasks share a **2-thread pool**.

| Service | Method | Schedule | What It Does |
|---------|--------|----------|--------------|
| `AwardService` | `computeDailyAwards()` | `0 1 0 * * ?` — daily 00:01 | Computes daily awards for yesterday. Resets `plays_today`. |
| `AwardService` | `computeWeeklyAwards()` | `0 1 0 * * MON` — Mondays 00:01 | Weekly awards. |
| `AwardService` | `computeMonthlyAwards()` | `0 1 0 1 * ?` — 1st of month 00:01 | Monthly awards. |
| `AwardService` | `computeQuarterlyAwards()` | `0 1 0 1 * ?` — 1st of month 00:01 | Self-filters to months 1/4/7/10. |
| `AwardService` | `computeMidtermAwards()` | `0 1 0 1 * ?` — 1st of month 00:01 | Self-filters to months 1/7. |
| `AwardService` | `computeAnnualAwards()` | `0 1 0 1 1 ?` — Jan 1st 00:01 | Annual awards. |
| `VoteService` | `computeDailyAwards()` | `0 0 0 * * ?` — daily 00:00 | **Duplicate — remove.** Fires 1 min before AwardService version, produces conflicting records. |
| `ScoreUpdateService` | `monthlyAgeBonuses()` | `0 0 0 1 * ?` — 1st of month 00:00 | +1 to every user with `monthsOld > 0`. Full table scan. |

**Risk:** On the 1st of each month, 4 jobs fire within the same minute with only 2 threads — at least 2 will queue.

---

## 9. Storage Architecture

```
FileStorageService (interface)
├── LocalFileStorageService   @Profile("local") → ./uploads/
└── CloudflareR2Service       @Profile("prod")  → Cloudflare R2 bucket
```

Switch via `spring.profiles.active` — no code changes needed.

**Known Gap:** `deleteFile` never called on song deletion, user deletion, or artwork replacement. Fix: call `fileStorageService.deleteFile(oldUrl)` in `deleteSong`, `deleteCurrentUserAndAllData`, and `updateSong` (artwork replacement).

---

## 10. Repository Layer

### `UserRepository`
| Method | Type | Description |
|--------|------|-------------|
| `findByEmail(email)` | JPQL | `Optional<User>` — general email lookup (includes soft-deleted users) |
| `findActiveByEmail(email)` | JPQL | `Optional<User>` — auth lookup filtered by `deleted_at IS NULL` (C5) |
| `findByUsername(username)` | Derived | Auth by username |
| `existsByEmail` / `existsByUsername` | Derived | Registration checks |
| `existsByReferralCode(code)` | Derived | Referral code uniqueness |
| `findByReferralCode(code)` | Derived | Referral validation |
| `findByIdWithJurisdiction(id)` | JPQL | Fetch join with jurisdiction only |
| `findByIdWithAssociations(id)` | JPQL | Fetch join with jurisdiction + genre |
| `findTopArtistsByJurisdictionWithHierarchy(jurisdictionId, limit)` | Native | Recursive CTE + join ordered by score |
| `computeUserScores()` | Native | Multi-subquery score computation |
| `updateUserScoreAndLevel(id, score, level)` | JPQL `@Modifying` | Batch score update |
| `incrementScore(id, increment)` | JPQL `@Modifying` | Real-time increment |
| `nullifySupportedArtistForListeners(artistId)` | JPQL `@Modifying` | Cleanup on artist deletion |
| `findByRoleOrderByScoreDesc(role)` | JPQL | Artists sorted by score |
| `findAllByRole(role)` | Derived | All users of a role |
| `findByRoleAndJurisdiction(role, jurisdictionId)` | JPQL | Filtered by role and jurisdiction |
| `countBySupportedArtistId(artistId)` | Derived | Supporter count for artist |

---

### `SongRepository`
| Method | Type | Description |
|--------|------|-------------|
| `findTopByJurisdictionWithHierarchy(jurisdictionId, limit)` | Native | Recursive CTE ordered by score |
| `computeSongScores()` | Native | Correlated subqueries across plays, votes, likes, awards |
| `incrementScore(id, increment)` | JPQL | Real-time increment |
| `findByArtistId(artistId)` | JPQL | Artist's songs ordered by `createdAt` DESC |
| `resetPlaysToday(today)` | JPQL | Bulk reset where `lastPlayResetDate < today` |
| `deleteByArtistUserId(artistId)` | JPQL | Cascade delete |

---

### `VideoRepository`
Mirrors `SongRepository`. **Refactor Flag:** No `resetPlaysToday` equivalent.

---

### `VoteRepository`
| Method | Type | Description |
|--------|------|-------------|
| `existsByUserAndCategoryAndJurisdictionAndIntervalAndDate(...)` | Native | Primary duplicate prevention — every submission |
| `findTopVoteCountsForRange(...)` / `findTopArtistVoteCountsForRange(...)` | Native | Core of `getLeaderboard` — most expensive query |
| `findByJurisdictionGenreInterval(...)` | Native | Recursive CTE + filters |
| `findByUserUserIdOrderByVoteDateDesc(userId)` | JPQL | Vote history |

**Refactor Flag:** Deprecated long-form duplicate-check method should be removed.

---

### `AwardRepository`
| Method | Type | Description |
|--------|------|-------------|
| `existsAwardForCategory(...)` | Native | Deduplication — every award cycle |
| `findByFilters(...)` | Native | All-filter query for milestones |
| `findWinnerForDate(...)` | Native | Single winner lookup |
| `findDistinctAwardDates(jurisdictionId)` | JPQL | Used by `recomputeAllHistoricalAwards` |
| `deleteByAwardDate(awardDate)` | JPQL | Destructive bulk delete — admin only |
| `findByTargetIdOrderByAwardDateDesc(targetId, pageable)` | JPQL | Paginated artist awards |

---

### `JurisdictionRepository`
| Method | Type | Description |
|--------|------|-------------|
| `findVotingEnabledJurisdictions()` | JPQL | All where `votingEnabled=true` |
| `canUserVoteInJurisdiction(userPath, targetJurisdictionId)` | Native | Path-based eligibility |
| `findVotingEnabledAncestors(userPath)` | Native | `LIKE` on materialized path |
| `findJurisdictionsContainingPoint(lat, lng)` | Native | PostGIS `ST_Contains` |
| `findParentChain(jurisdictionId)` | Native | Recursive CTE ancestor breadcrumb |
| `hasChildren(parentId)` | JPQL | Boolean drill-down check |

**Refactor Flag:** PostGIS must be confirmed on Supabase. GIN index on `path` column needed.

---

### `GenreRepository` / `VotingIntervalRepository`
Minimal — `findByName` and `findAllIds`. `VotingIntervalRepository` results should be cached (static config data).

---

### `PlaylistRepository` / `PlaylistTrackRepository`
`findByUser(user)` — add `findByUser_UserId(UUID)` variant. `PlaylistTrackRepository` maps to `playlist_items` — confirm `@Table(name = "playlist_items")` on entity.

---

### `CommentRepository`
| Method | Type | Description |
|--------|------|-------------|
| `findTopLevelCommentsBySongId(songId)` | JPQL | Non-reply, `deletedAt IS NULL`, with user fetch |
| `findTopLevelCommentsBySongIdPaginated(songId, pageable)` | JPQL | Paginated variant |
| `softDelete(commentId, now)` | JPQL `@Modifying` | Sets `deletedAt` |
| `isCommentOwner(commentId, userId)` | JPQL | Authorization check |
| `findRecentComments(pageable)` | JPQL | Platform-wide — admin only |

**Refactor Flag:** Add `@Where(clause = "deleted_at IS NULL")` to entity as safety net.

---

### `FollowRepository`
All derived queries. `countByFollowed_UserId`, `countByFollower_UserId`, `existsByFollower_UserIdAndFollowed_UserId`, `deleteByFollower_UserIdAndFollowed_UserId`.

---

### `SupporterRepository`
| Method | Type | Description |
|--------|------|-------------|
| `findByListenerId(listenerId)` | JPQL | Single record — should be `Optional<Supporter>` |
| `countByArtistId(artistId)` | JPQL | Feeds +5 per supporter in scoring |
| `deleteByArtistUserId` / `deleteByListenerUserId` | JPQL `@Modifying` | Cascade delete |

---

### `LikeRepository`
`existsByUser_UserIdAndMediaTypeAndMediaId` (dedup), `deleteByUserUserId` (account deletion).
**Refactor Flag:** No `deleteByMediaTypeAndMediaId` — orphaned likes on media deletion.

---

### `SongPlayRepository`
| Method | Type | Description |
|--------|------|-------------|
| `countTotalPlaysBySongId(songId)` | Native | Lifetime play count |
| `findTrendingByJurisdiction(jurisdictionId, limit)` | Native | Top songs by plays today — joins through artist's jurisdiction |
| `countPlaysToday()` | Native | Platform-wide — full table scan |
| `deleteByUserUserId(userId)` | JPQL `@Modifying` | Account deletion cleanup |

**Refactor Flag:** `findTrendingByJurisdiction` filters by artist's jurisdiction, not viewer's — verify intent.

---

### `VideoPlayRepository`
Mirrors `SongPlayRepository`. **Refactor Flag:** Missing `countTotalPlaysByVideoId`.

---

### `AdViewRepository`
`sumEarningsByDay`, `sumEarningsByDayFromReferrals` — both can return `null`, add `COALESCE`. `getEarningsLastDays` — GROUP BY day for N-day window.

---

### `ReferralRepository`
`countByReferrer(userId)`, `existsByReferrerAndReferred`.
**Refactor Flag:** No cascade delete — add `deleteByReferrerUserId` and `deleteByReferredUserId`.

---

## 11. Query Complexity Reference

| Rank | Method | Repository | Cost |
|------|--------|------------|------|
| 1 | `findTopArtistsByJurisdictionWithHierarchy` | User | High — recursive CTE + join |
| 2 | `computeUserScores` | User | High — multi correlated subqueries |
| 3 | `findTopByJurisdictionWithHierarchy` | Song/Video | High — recursive CTE |
| 4 | `computeSongScores`/`computeVideoScores` | Song/Video | High — correlated subqueries |
| 5 | `findTopVoteCountsForRange` | Vote | High — GROUP BY on large table |
| 6 | `findByJurisdictionGenreInterval` | Vote | Medium-High — recursive CTE + filters |
| 7 | `findJurisdictionsContainingPoint` | Jurisdiction | Medium-High — PostGIS spatial |
| 8 | `findParentChain`/`findByTier` | Jurisdiction | Medium — recursive CTE |
| 9 | `findTrendingByJurisdiction` | SongPlay/VideoPlay | Medium — 3-table join filtered to today |
| 10 | `getEarningsLastDays` | AdView | Medium — GROUP BY day |
| 11 | `findVotingEnabledAncestors` | Jurisdiction | Low-Medium — LIKE on path |
| 12 | `existsByUserAndCategory...` | Vote | Low but very high frequency |
| 13 | `countPlaysToday` | SongPlay/VideoPlay | Low-Medium — full table scan, grows |

---

## 12. Missing DB Indexes

| Table | Column(s) | Index Type | Priority |
|-------|-----------|------------|----------|
| `users` | `email` | Unique | Critical |
| `users` | `username` | Unique | Critical |
| `users` | `referral_code` | Unique | Critical |
| `users` | `role`, `jurisdiction_id`, `score` | B-tree | High |
| `songs` | `artist_id` | B-tree | Critical |
| `songs` | `jurisdiction_id`, `score` | B-tree | High |
| `songs` | `last_play_reset_date` | B-tree | Medium |
| `videos` | `artist_id`, `jurisdiction_id`, `score` | B-tree | Critical/High |
| `votes` | `(jurisdiction_id, interval_id, vote_date, target_type)` | Composite | Critical |
| `votes` | `user_id`, `genre_id` | B-tree | High/Medium |
| `awards` | `(target_type, target_id, jurisdiction_id, interval_id, award_date)` | Composite | Critical |
| `awards` | `(jurisdiction_id, interval_id)` | Composite | High |
| `jurisdictions` | `parent_jurisdiction_id`, `voting_enabled` | B-tree | High/Medium |
| `jurisdictions` | `path` | GIN | High |
| `jurisdictions` | geometry/polygon | GIST (PostGIS) | High |
| `song_plays` | `(song_id, played_at)` | Composite | Critical |
| `song_plays` | `user_id` | B-tree | Medium |
| `video_plays` | `(video_id, played_at)` | Composite | Critical |
| `likes` | `(user_id, media_type, media_id)` | Composite Unique | High |
| `likes` | `(media_type, media_id)` | Composite | High |
| `ad_views` | `(supported_artist_id, viewed_at)` | Composite | High |
| `referrals` | `(referrer_id, referred_id)` | Composite Unique | High |

---

## 13. Utility Classes

### `SecurityUtils.java` (NEW — C6)
**Package:** `com.unis.util`
**Purpose:** Extracts the authenticated user's UUID from the Spring SecurityContext. Used by all controllers after C6 fix to derive userId from JWT instead of client-supplied values.

| Method | Description |
|--------|-------------|
| `getAuthenticatedUserId()` | Returns `UUID` from the credentials field of `UsernamePasswordAuthenticationToken`, which is populated by `JwtRequestFilter` from the JWT `userId` claim. |

**Requires:** C6 change in `JwtRequestFilter` that stores userId as credentials.

---

### `ReferralCodeGenerator.java`
Stateless utility. Generates `USERNAME-XXXXX` format codes using `SecureRandom`. No Spring annotations.

| Method | Description |
|--------|-------------|
| `generate(username)` | Sanitizes (uppercase, strip non-alphanumeric, cap 20 chars), appends 5-char random suffix |
| `generateUnique(username, existsCheck)` | Retry loop max 10 attempts. Falls back to double-length random suffix. |

**C7 Fix Applied:** `generate()` now uses `sanitized.length()` instead of `username.length()` in the substring call, preventing `StringIndexOutOfBoundsException` with special-character usernames.

**L13 Fix Applied:** `generateUnique()` fallback now uses a 10-character random suffix instead of `System.currentTimeMillis()`, eliminating guessable codes.

---

## 14. Action Items

### 🔴 Critical — Security / Data Integrity

| # | Issue | Location | Status |
|---|-------|----------|--------|
| C1 | `POST /api/v1/media/song` was public | `SecurityConfig`, `MediaController` | ✅ FIXED — requires auth, artistId from JWT |
| C2 | `POST /api/v1/awards/recompute-all` was public and destructive | `SecurityConfig`, `AwardController` | ✅ FIXED — requires `ROLE_ADMIN` |
| C3 | All `/api/v1/admin/*` endpoints were public | `SecurityConfig`, `AdminController` | ✅ FIXED — requires `ROLE_ADMIN` |
| C4 | `PUT /profile/{userId}/*` no ownership check | `UserController` | ✅ FIXED — validates JWT userId matches path userId, returns 403 on mismatch |
| C5 | Soft-deleted users could still authenticate | `UserRepository`, `UserDetailsServiceImpl` | ✅ FIXED — `findActiveByEmail` filters `deleted_at IS NULL` |
| C6 | `userId` supplied by client not JWT | `JwtRequestFilter`, `SecurityUtils` (new), `VoteController`, `MediaController`, `CommentController`, `MediaService` | ✅ FIXED — all mutations use `SecurityUtils.getAuthenticatedUserId()` |
| C7 | `StringIndexOutOfBoundsException` in `ReferralCodeGenerator` | `ReferralCodeGenerator.java` | ✅ FIXED — uses `sanitized.length()` |

---

### 🟠 Medium — Bugs / Performance

| # | Issue | Location | Action |
|---|-------|----------|--------|
| M1 | Duplicate `computeDailyAwards` fires at 00:00 and 00:01 — conflicting Award records | `VoteService` | Remove from `VoteService`. |
| M2 | `deleteFile` never called — orphaned files accumulate | `MediaService`, `UserService` | Call `fileStorageService.deleteFile(oldUrl)` on delete and artwork replacement. |
| M3 | `getIntervalStartDate` Daily returns yesterday | `VoteService` | Revert to `LocalDate.now()` before launch. |
| M4 | `SupporterRepository.findByListenerId` returns bare type not `Optional` | `SupporterRepository` | Change return type. |
| M5 | `PlaylistTrack` must declare `@Table(name = "playlist_items")` | `PlaylistTrack` entity | Confirm annotation present. |
| M6 | No cascade delete for `Referral` records | `ReferralRepository` | Add `deleteByReferrerUserId` and `deleteByReferredUserId`. |
| M7 | No cleanup path for `Like` records on media deletion | `LikeRepository` | Add `deleteByMediaTypeAndMediaId`. |
| M8 | `sumEarningsByDay` can return null | `AdViewRepository` | Add `COALESCE(..., 0)`. |
| M9 | `countPlaysToday()` full table scan | `SongPlayRepository`, `VideoPlayRepository` | Add partial index on `played_at`. |
| M10 | Trending filters by artist's jurisdiction, not viewer's | `SongPlayRepository.findTrendingByJurisdiction` | Verify and fix join if needed. |
| M11 | Missing `countTotalPlaysByVideoId` | `VideoPlayRepository` | Add for parity. |
| M12 | PostGIS not confirmed on Supabase | `JurisdictionRepository` | Verify `CREATE EXTENSION postgis`. |
| M13 | `reorderPlaylist` silently drops unlisted tracks | `PlaylistService` | Validate all existing track IDs present in input. |
| M14 | Active jurisdictions hardcoded | `JurisdictionService` | Add `is_active` column to `jurisdictions` table before expansion. |
| M15 | `javax.annotation.PostConstruct` in Spring Boot 3.x | `CloudflareR2Service` | Replace with `jakarta.annotation.PostConstruct`. |

---

### 🟡 Low — Refactors / Cleanup

| # | Issue | Location | Action |
|---|-------|----------|--------|
| L1 | `CorsConfig.java` redundant | `CorsConfig.java` | Remove — `SecurityConfig` already handles CORS. |
| L2 | JJWT deprecated setters | `JwtUtil.java` | Upgrade to 0.12+ builder pattern. |
| L3 | `System.out.println` throughout | `AwardService`, `JwtRequestFilter` | Replace with SLF4J. |
| L4 | `RegisterRequest` DTO unused | `RegisterRequest.java` | Delete. |
| L5 | `AwardDto` unused in controller | `AwardController` | Map `Award` entity to `AwardDto`. |
| L6 | `UserDto` serves too many purposes | `UserDto.java` | Split into purpose-specific DTOs. |
| L7 | `PlaylistController` missing `/v1/` | `PlaylistController` | Change to `/api/v1/playlists`. |
| L8 | `findRecentComments` unguarded | `CommentController` | Add `ROLE_ADMIN`. |
| L9 | Comment soft delete not backed by `@Where` | `Comment` entity | Add `@Where(clause = "deleted_at IS NULL")`. |
| L10 | Deprecated vote duplicate-check method | `VoteRepository` | Confirm no call sites, delete. |
| L11 | `PlaylistRepository.findByUser` requires full `User` | `PlaylistRepository` | Add `findByUser_UserId(UUID)`. |
| L12 | `VotingIntervalRepository` results not cached | `VotingIntervalRepository` | Add Caffeine cache — static config data. |
| L13 | ~~`ReferralCodeGenerator` timestamp fallback guessable~~ | `ReferralCodeGenerator` | ✅ FIXED alongside C7. |
| L14 | No minimum prefix length guard | `ReferralCodeGenerator` | Pad prefix to min 3 chars. |
| L15 | `createFallbackAwards` calls `findAll()` | `AwardService` | Replace with top-N score query. |
| L16 | `PATCH /profile/photo` temp endpoint present | `UserController` | Remove or secure before launch. |
| L17 | Hardcoded `"UNIS-LAUNCH-2024"` referral bypass | `UserController` | Remove before launch. |

---

*End of Backend Architecture Documentation*
