# Unis — Open Issues

The one place issues are tracked, for web (`unis-sample`), backend
(`unis_mvp/backend`) and mobile. It replaces the action-items table in
`ARCHITECTURE.md` and `unis-sample/docs/QA_FINDINGS.md`.

Last checked against code: **2026-11-10** (web `master` @ `ddccdf4`,
backend `main` @ `6911cef`, plus the Part 1–2 cleanup delivered that day).

Severity: 🔴 before launch · 🟠 soon · 🟡 when convenient · ⚪ unverified

---

## Open

### Deploy steps for the 2026-11-10 changes

| # | Item | Area | What to do |
|---|------|------|------------|
| D1 | Waitlist rows saved with the retired `UNIS-LAUNCH-2024` code no longer credit anyone | DB | Run `SELECT COUNT(*) FROM pre_registrations WHERE upper(referred_by) = 'UNIS-LAUNCH-2024';`. If above 0, make a short code you own in Codes admin and point those rows at it. |
| D2 | Open-flag SQL not yet applied | DB | Run the "LOOK FIRST" query, then `backend/sql/V2026_11a__jurisdiction_open_flag.sql`, before deploying the Find page change. |
| D3 | Stripe Connect destination for `account.updated` | Stripe / Railway | Workbench → Webhooks → Create an event destination, Events from **Connected accounts**, event `account.updated`, same URL. Put its secret in `STRIPE_CONNECT_WEBHOOK_SECRET`. Do it in test and live mode. |

### Security

| # | Issue | Area | Notes |
|---|-------|------|-------|
| S1 | 🔴 Password-reset and email-verify URLs are written to the logs when sending fails (`FALLBACK RESET URL` / `FALLBACK VERIFY URL`) | Backend `EmailService` | Anyone who can read Railway logs can reset that account's password. Log that it failed, not the link. |
| S2 | 🟡 Dev-only origins are also allowed in production: `http://localhost:*` on the WebSocket endpoint, and `localhost` / `127.0.0.1` in the CORS list | Backend `WebSocketConfig`, `SecurityConfig` | Low risk. Allow them only under a dev profile. |
| S3 | 🟡 `POST /media/play/complete` doesn't check that the play belongs to the caller | Backend `MediaController` / `MediaService.completeSongPlay` | Only feeds fan analytics (`completed`, `percent_played`), not rankings, and play ids are random UUIDs. Check the owner when the caller is logged in. |

### Expansion (before the second neighborhood opens)

| # | Issue | Area | Notes |
|---|-------|------|-------|
| E1 | 🟠 Unis-level awards aren't defined | Product + backend | Voting allows any `voting_enabled` jurisdiction in your chain, but the web only offers the three Harlem places, and nothing decides whether Unis awards are voted, automatic or none. Today Harlem effectively is Unis. Decide this at the next expansion. |
| E2 | 🟠 Harlem is still hardcoded outside the Find page | Web | `chartsPage.jsx` (PLACES), `voteawards.jsx` (jurisdictions), `DiscoverPage.jsx`, `feed.jsx`, `BrowseTagPage.jsx`, `milestonesPage.jsx`, `createAccountWizard.jsx` (Uptown/Downtown choice), `admin/RankingsAdmin.jsx`, `utils/idMappings.js` (JURISDICTION_IDS), and `findpage.jsx` `chartPlace` (the "full chart" link). These should read `GET /v1/jurisdictions/open` as the Find page now does. |
| E3 | 🟡 The open list is cached for 10 minutes | Backend | Opening a neighborhood in SQL shows within 10 minutes, or right away after a restart. Fine as is; noted so it isn't mistaken for a bug. |

### Tests

| # | Issue | Area | Notes |
|---|-------|------|-------|
| T1 | 🟠 Web tests updated on 2026-11-10 haven't been run | Web | `winnersTimeline`, `profile`, `artistpage`, `findpage`, `voteawards`, `createAccountWizard`. Some `findpage.test.jsx` cases were already stale (they mock `/tops`; the page uses `/v1/awards/standings`). |
| T2 | ⚪ Backend tests not run against the 2026-11-10 changes | Backend | Run `./mvnw test`. `BaseIntegrationTest` still has a `System.out` (test code, left as is). |

### Bugs and cleanup

| # | Issue | Area | Notes |
|---|-------|------|-------|
| B1 | 🟡 Replaced song/video artwork and profile photos leave the old file in storage | Backend `MediaService` (song edit, video), `UserService.updatePhoto` | Delete the old file after the new one saves (was ARCHITECTURE M2). |
| B2 | 🟡 Payout history labels every payout "last month" | Backend `StripeConnectService.requestPayout` | `periodStart` / `periodEnd` are hardcoded (was QA 5d). |
| B3 | 🟡 Sign-up wizard's preview progress bar and auto-reset never work | Web `createAccountWizard.jsx` | The `timeupdate` / `ended` effect has `[]` deps and runs before `<audio>` exists (was QA 10c). |
| B4 | 🟡 No `--unis-warning` / `--unis-success` design tokens | Web | Two literal colors remain in the wizard (was QA 10d). |
| B5 | 🟡 `SupporterRepository.findByListenerId` returns a bare `Supporter`, not `Optional` | Backend | Was ARCHITECTURE M4. |
| B6 | 🟡 `countPlaysToday()` uses `DATE(played_at) = CURRENT_DATE`, so it can't use `idx_song_plays_played_at` | Backend `SongPlayRepository`, `VideoPlayRepository` | Compare against a timestamp range instead (was M9). |
| B7 | 🟡 `VideoPlayRepository.findTrendingByJurisdiction` is unused | Backend | Same dead query as the song one removed 2026-11-10. |
| B8 | 🟡 JJWT 0.12.3 but still the deprecated `setSubject` / `setIssuedAt` / `setExpiration` | Backend `config/JwtUtil.java` | Was L2. |
| B9 | 🟡 `UserDto` serves too many purposes | Backend | Was L6. |
| B10 | 🟡 `PlaylistRepository.findByUser(User)` needs a full `User` | Backend | Add `findByUser_UserId(UUID)` (was L11). |
| B11 | 🟡 `VotingIntervalRepository` results aren't cached | Backend | Static data (was L12). |
| B12 | 🟡 Referral code prefix has no minimum length | Backend `ReferralCodeGenerator` | Was L14. |
| B13 | ⚪ PostGIS on Supabase not confirmed in docs | Backend `JurisdictionRepository.findJurisdictionsContainingPoint` | If sign-up location assignment works in prod, close this (was M12). |

### Mobile (private repo — open, unverified from here)

| # | Issue | Notes |
|---|-------|-------|
| MB1 | ⚪ 🔴 Three stale imports break bundling | `AppNavigator`: `'../screens/MessagesScreen'` → `MessageScreen`. `UnisMap`: `'./geometry/usStates'` → `'./usStates'`. `FindScreen`: `'../map/UnisMap'` → `'../components/UnisMap'`. |
| MB2 | ⚪ 🟠 No test runner in the mobile repo | |
| MB3 | ⚪ 🟠 Mobile hasn't caught up with web | Community Share earnings and more. It also needs the 2026-11-10 web changes: Player-only play counting, no launch code, and the open-neighborhood list from `GET /v1/jurisdictions/open`. |

---

## Closed

Items closed on 2026-11-10 were fixed that day. "Verified 2026-11-10" means
it had been fixed earlier and was confirmed against the code that day. "Per
Charles" means it was reported fixed and wasn't re-checked line by line.

| Closed | Item |
|--------|------|
| 2026-11-10 | Pages POSTing `/play` themselves (songPage, winnersTimeline, artistpage, profile); unused `playTracker.js`; `?userId=` in the Player's play URL. The Player is now the only thing that records a song play. (QA Finding 3) |
| 2026-11-10 | Master referral code `UNIS-LAUNCH-2024` removed from web and backend (L17) |
| 2026-11-10 | `app.frontend.url` fallbacks now `https://unismusic.com` in StripeConnect, Sponsor, Plus, SponsorController and WebSocketConfig (QA 5c) |
| 2026-11-10 | Open neighborhoods come from `jurisdictions.voting_enabled`, not hardcoded lists, in JurisdictionService and findpage, with auto drill-down to the one open neighborhood (M14) |
| 2026-11-10 | `createFallbackAwards` queries the top 10 instead of `findAll()`, for songs and artists (L15) |
| 2026-11-10 | Stripe `account.updated` webhook updates payout-setup status right away (QA 5b) |
| 2026-11-10 | `System.out` / `printStackTrace` replaced with SLF4J across 11 backend files (L3) |
| 2026-11-10 | `jakarta.annotation.PostConstruct` in CloudflareR2Service (M15) |
| 2026-11-10 | Removed unused `RegisterRequest`, `AwardDto`, the deprecated duplicate-vote query, and `SongPlayRepository.findTrendingByJurisdiction` (L4, L5, L10, M10) |
| Per Charles, 2026-11-10 | Earnings balance and monthly total (QA 2); cashout balance (QA 5a) |
| Verified 2026-11-10 | Award timing and duplicate daily cron, now on the race clock and `RaceCloseScheduler` (QA 4, M1, M3) |
| Verified 2026-11-10 | Song and account deletion (soft delete, FK-safe) (QA 7, M6, M7) |
| Per Charles, 2026-11-10 | Leaderboard score inflation (QA 8) |
| Per Charles, 2026-11-10 | Purchase revenue reaches payouts (QA 9) |
| Per Charles, 2026-11-10 | Ad view tracking coverage (QA 1) |
| Verified 2026-11-10 | Genre ID duplicates: dropdowns iterate `CANONICAL_GENRES` (QA 10a) |
| Verified 2026-11-10 | WinnersNotification reads `/v1/awards/past`, not live standings (QA 6) |
| Verified 2026-11-10 | Wizard `onSuccess` is called (QA 10b) |
| Verified 2026-11-10 | Play crediting takes the user from the login token, not `?userId=` (play-ID spoofing) |
| Verified 2026-11-10 | `sumEarningsByDay` uses COALESCE (M8); `PlaylistTrack` maps `playlist_items` (M5); `countTotalPlaysByVideoId` exists (M11); `reorderPlaylist` rejects partial lists (M13) |
| Verified 2026-11-10 | `CorsConfig` removed (L1); `/api/v1/playlists` path (L7); recent-comments endpoint gone, admin comments need a role (L8); Comment `@Where` soft-delete filter (L9); `PATCH /profile/photo` validates type and size and is rate-limited (L16) |
| Verified 2026-11-10 | Security C1–C7 and L13 (see ARCHITECTURE history) |
| Per Charles, 2026-11-10 | Messaging fixes (not re-checked here) |
