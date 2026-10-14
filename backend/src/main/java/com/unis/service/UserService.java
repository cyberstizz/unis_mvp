package com.unis.service;

import com.unis.entity.Vote;
import com.unis.entity.User;
import com.unis.entity.Referral;
import com.unis.entity.Song;
import com.unis.entity.Supporter;
import com.unis.repository.UserRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.VideoRepository;
import com.unis.repository.VoteRepository;
import com.unis.repository.AwardRepository;
import com.unis.repository.SongPlayRepository;
import com.unis.repository.VideoPlayRepository;
import com.unis.repository.LikeRepository;
import com.unis.repository.AdViewRepository;
import com.unis.service.ScoreUpdateService;
import com.unis.util.ReferralCodeGenerator;
import com.unis.repository.ReferralRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import com.unis.repository.SupporterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unis.dto.ProfileSummaryDto;
import java.util.ArrayList;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;


@Service
@Transactional
public class UserService {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SupporterRepository supporterRepository;

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private AwardRepository awardRepository;

    @Autowired
    private SongPlayRepository songPlayRepository;

    @Autowired
    private VideoPlayRepository videoPlayRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private AdViewRepository adViewRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired 
    private EntityManager entityManager;

    @Autowired 
    private ScoreUpdateService scoreUpdateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MediaService mediaService;

    @Autowired
    private EarningsService earningsService;

    @Autowired
    private com.unis.repository.PayoutRepository payoutRepository;

    /**
     * Usernames are reserved forever, ignoring capitalization: an account
     * that's live, deleted (past awards still show its name), or waiting on the
     * waitlist all hold their name. A waitlist name is free to the same person
     * (same email). The database enforces the same rule (V2026_10b).
     *
     * @param email the person signing up, or null when it isn't known yet
     */
    @Transactional(readOnly = true)
    public boolean isUsernameTaken(String username, String email) {
        if (username == null || username.isBlank()) return true;
        String name = username.trim();
        Boolean onUsers = jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM users WHERE lower(username) = lower(?))", Boolean.class, name);
        if (Boolean.TRUE.equals(onUsers)) return true;
        Boolean onWaitlist = email == null
            ? jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pre_registrations WHERE lower(username) = lower(?))",
                Boolean.class, name)
            : jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pre_registrations WHERE lower(username) = lower(?) "
                    + "AND lower(email) <> lower(?))",
                Boolean.class, name, email);
        return Boolean.TRUE.equals(onWaitlist);
    }



    @Cacheable(value = "profileSummaries", key = "#userId")
    public ProfileSummaryDto getProfileSummary(UUID userId) {
        long startNs = System.nanoTime();
    
        User user = userRepository.findByIdWithJurisdiction(userId)
            .orElseThrow(() -> new RuntimeException("User not found: " + userId));
    
        if (user.getDeletedAt() != null) {
            throw new RuntimeException("User is deleted: " + userId);
        }
    
        // ---- Self profile ------------------------------------------------------
        ProfileSummaryDto.SelfProfile selfProfile = ProfileSummaryDto.SelfProfile.builder()
            .userId(user.getUserId())
            .username(user.getUsername())
            .email(user.getEmail())
            .bio(user.getBio())
            .photoUrl(user.getPhotoUrl())
            .score(user.getScore())
            .level(user.getLevel())
            .themePreference(user.getThemePreference())
            .role(user.getRole() != null ? user.getRole().toString() : null)
            .supportedArtistId(user.getSupportedArtistId())
            .instagramUrl(user.getInstagramUrl())
            .twitterUrl(user.getTwitterUrl())
            .tiktokUrl(user.getTiktokUrl())
            .youtubeUrl(user.getYoutubeUrl())
            .contactEmail(user.getContactEmail())
            .createdAt(user.getCreatedAt())
            .jurisdiction(user.getJurisdiction() == null ? null :
                ProfileSummaryDto.JurisdictionInfo.builder()
                    .jurisdictionId(user.getJurisdiction().getJurisdictionId())
                    .name(user.getJurisdiction().getName())
                    .build())
            .build();
    
        // ---- Supported artist (optional) --------------------------------------
        ProfileSummaryDto.SupportedArtistInfo supportedArtist = null;
        if (user.getSupportedArtistId() != null) {
            Optional<User> artistOpt = userRepository.findById(user.getSupportedArtistId());
            if (artistOpt.isPresent()) {
                User artist = artistOpt.get();
                ProfileSummaryDto.DefaultSongInfo defaultSong = null;
                if (artist.getDefaultSongId() != null) {
                    Optional<Song> songOpt = songRepository.findById(artist.getDefaultSongId());
                    if (songOpt.isPresent()) {
                        Song s = songOpt.get();
                        defaultSong = ProfileSummaryDto.DefaultSongInfo.builder()
                            .songId(s.getSongId())
                            .title(s.getTitle())
                            .fileUrl(s.getFileUrl())
                            .artworkUrl(s.getArtworkUrl())
                            .duration(s.getDuration())
                            .build();
                    }
                }
                supportedArtist = ProfileSummaryDto.SupportedArtistInfo.builder()
                    .userId(artist.getUserId())
                    .username(artist.getUsername())
                    .photoUrl(artist.getPhotoUrl())
                    .defaultSong(defaultSong)
                    .build();
            } else {
                // Stale supportedArtistId pointing to a deleted user — log and continue.
                System.out.println("[ProfileSummary] WARNING: user " + userId
                    + " has supportedArtistId " + user.getSupportedArtistId()
                    + " but that artist was not found");
            }
        }



        // ---- Pending supported-artist change (queued, not yet effective) -------
        ProfileSummaryDto.PendingSupportedArtistInfo pendingSupportedArtist = null;
        if (user.getPendingSupportedArtistId() != null) {
            Optional<User> pendingOpt = userRepository.findById(user.getPendingSupportedArtistId());
            if (pendingOpt.isPresent() && pendingOpt.get().getDeletedAt() == null) {
                User p = pendingOpt.get();
                pendingSupportedArtist = ProfileSummaryDto.PendingSupportedArtistInfo.builder()
                    .userId(p.getUserId())
                    .username(p.getUsername())
                    .photoUrl(p.getPhotoUrl())
                    .effectiveDate(java.time.LocalDate.now(java.time.ZoneId.of("America/New_York"))
                        .plusMonths(1).withDayOfMonth(1).atStartOfDay())
                    .build();
            }
        }
    
        // ---- Vote history -----------------------------------------------------
        ProfileSummaryDto.VoteHistorySummary voteHistory;
        try {
            List<Vote> userVotes = voteRepository.findByUserUserIdOrderByVoteDateDesc(userId);
            voteHistory = ProfileSummaryDto.VoteHistorySummary.builder()
                .totalCount(userVotes.size())
                .recent(new java.util.ArrayList<>())  // empty for now; modal can fetch its own list
                .build();
        } catch (Exception e) {
            System.out.println("[ProfileSummary] WARNING: vote history failed for " + userId + ": " + e.getMessage());
            voteHistory = ProfileSummaryDto.VoteHistorySummary.builder()
                .totalCount(0)
                .recent(new java.util.ArrayList<>())
                .build();
        }

        // ---- Settings (preference toggles, backed by real columns) ------------
        ProfileSummaryDto.Settings settings = ProfileSummaryDto.Settings.builder()
            .emailNotifications(user.getEmailNotifications() != null ? user.getEmailNotifications() : true)
            .publicProfile(user.getPublicProfile() != null ? user.getPublicProfile() : true)
            .showVoteHistory(user.getShowVoteHistory() != null ? user.getShowVoteHistory() : false)
            .build();

        long durationMs = (System.nanoTime() - startNs) / 1_000_000;
        System.out.println("[ProfileSummary] action=fetch userId=" + userId
            + " status=ok durationMs=" + durationMs
            + " hasArtist=" + (supportedArtist != null));
    
        return ProfileSummaryDto.builder()
            .profile(selfProfile)
            .supportedArtist(supportedArtist)
            .pendingSupportedArtist(pendingSupportedArtist)
            .voteHistory(voteHistory)
            .referralCode(user.getReferralCode())
            .settings(settings)
            .build();
    }

    // -----------------------------------------------------------------------
    // Preference toggles (AccountSettings). Whitelist of three keys IS the
    // security boundary — any other key in the payload is ignored, so a
    // malicious { "role": "admin" } can never reach the entity.
    // -----------------------------------------------------------------------
    @CacheEvict(value = "profileSummaries", key = "#userId")
    public ProfileSummaryDto.Settings updatePreferences(UUID userId, Map<String, Boolean> updates) {
        long startNs = System.nanoTime();
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        boolean changed = false;
        if (updates.containsKey("emailNotifications") && updates.get("emailNotifications") != null) {
            user.setEmailNotifications(updates.get("emailNotifications"));
            changed = true;
        }
        if (updates.containsKey("publicProfile") && updates.get("publicProfile") != null) {
            user.setPublicProfile(updates.get("publicProfile"));
            changed = true;
        }
        if (updates.containsKey("showVoteHistory") && updates.get("showVoteHistory") != null) {
            user.setShowVoteHistory(updates.get("showVoteHistory"));
            changed = true;
        }

        if (changed) {
            userRepository.save(user);
        } else {
            System.out.println("[Preferences] action=update userId=" + userId
                + " status=noop reason=no_valid_keys keys=" + updates.keySet());
        }

        long ms = (System.nanoTime() - startNs) / 1_000_000;
        System.out.println("[Preferences] action=update userId=" + userId
            + " status=ok changed=" + changed + " durationMs=" + ms);

        return ProfileSummaryDto.Settings.builder()
            .emailNotifications(user.getEmailNotifications())
            .publicProfile(user.getPublicProfile())
            .showVoteHistory(user.getShowVoteHistory())
            .build();
    }

    // -----------------------------------------------------------------------
    // One-click email unsubscribe. We only have the token (clicked from an
    // email, no session), so a full profileSummaries evict is acceptable —
    // unsubscribe is rare and we can't target the specific userId key here.
    // -----------------------------------------------------------------------
    @CacheEvict(value = "profileSummaries", allEntries = true)
    public boolean unsubscribeByToken(UUID token) {
        Optional<User> opt = userRepository.findByUnsubscribeToken(token);
        if (opt.isEmpty()) {
            System.out.println("[Preferences] action=unsubscribe status=noop reason=token_not_found");
            return false;
        }
        User user = opt.get();
        user.setEmailNotifications(false);
        userRepository.save(user);
        System.out.println("[Preferences] action=unsubscribe userId=" + user.getUserId() + " status=ok");
        return true;
    }

// Register new user - NOT CACHED (write operation)
    public User register(User newUser, UUID supportedArtistId, String referralCode) {
         // Reject an email that already belongs to another account (active or not)
         if (newUser.getEmail() != null
                 && userRepository.findByEmail(newUser.getEmail().toLowerCase().trim()).isPresent()) {
             throw new RuntimeException("An account with this email already exists.");
         }
        // === NEW: Date of birth validation ===
        if (newUser.getDateOfBirth() != null) {
            LocalDate today = LocalDate.now();
            // Calculate age
            int age = today.getYear() - newUser.getDateOfBirth().getYear();
            LocalDate birthdayThisYear = newUser.getDateOfBirth().withYear(today.getYear());
            if (today.isBefore(birthdayThisYear)) {
                age--;
            }
 
            // Reject under 13
            if (age < 13) {
                throw new RuntimeException("You must be at least 13 years old to join Unis.");
            }
 
            // Under 18: force explicit content disabled
            if (age < 18) {
                newUser.setExplicitContentEnabled(false);
            }
        }
        // === END NEW ===
 
        newUser.setEmail(newUser.getEmail().toLowerCase().trim());   // normalize before saving

        // Usernames are reserved forever, in any capitalization, including names
        // held by deleted accounts and by the waitlist.
        if (newUser.getUsername() == null || newUser.getUsername().isBlank()) {
            throw new RuntimeException("A username is required.");
        }
        newUser.setUsername(newUser.getUsername().trim());
        if (isUsernameTaken(newUser.getUsername(), newUser.getEmail())) {
            throw new RuntimeException("That username is taken.");
        }

        // Hash password
        newUser.setPasswordHash(passwordEncoder.encode(newUser.getPasswordHash()));
        newUser.setCreatedAt(LocalDateTime.now());
        newUser.setScore(0);
        newUser.setLevel("silver");
        if (newUser.getThemePreference() == null || newUser.getThemePreference().isBlank()) {  // ★
            newUser.setThemePreference("blue");   // default only — honor the user's pick
        } 
        // Generate a referral code for this user
        String uniqueReferralCode = ReferralCodeGenerator.generateUnique(
            newUser.getUsername(),
            code -> userRepository.existsByReferralCode(code)
        );
        newUser.setReferralCode(uniqueReferralCode);
 
        // Save user
        User savedUser = userRepository.save(newUser);
 
        // Handle referral tracking
        boolean referralTracked = false;   // feeds signup_source below
        if (referralCode != null && !referralCode.trim().isEmpty()) {
            // A deleted account's code no longer refers anyone.
            Optional<User> referrerOpt = userRepository.findByReferralCode(referralCode)
                    .filter(r -> r.getDeletedAt() == null);
            
            if (referrerOpt.isPresent()) {
                User referrer = referrerOpt.get();
                
                // Create referral record
                Referral referral = Referral.builder()
                        .referrer(referrer)
                        .referred(savedUser)
                        .createdAt(LocalDateTime.now())
                        .build();
                referralRepository.save(referral);
                referralTracked = true;
                
                // Award points to referrer (+5 for listeners, +2 for artists).
                // Recorded so it can be taken back if this account is deleted
                // within 30 days.
                scoreUpdateService.onReferral(referrer.getUserId(), savedUser.getUserId());
                
                System.out.println("Referral tracked: " + referrer.getUsername() + " referred " + savedUser.getUsername());
            } else {
                System.out.println("Warning: Referral code '" + referralCode + "' not found. Proceeding without referral.");
            }
        }
 
        // For ALL users (listeners AND artists): Validate and set supported artist
        if (supportedArtistId != null) {
            Optional<User> optionalArtist = userRepository.findById(supportedArtistId)
                    .filter(a -> a.getDeletedAt() == null);
            User supportedArtist = optionalArtist.orElseThrow(() -> new RuntimeException("Supported artist not found"));
            
            if (!"artist".equals(supportedArtist.getRole().toString())) {
                throw new RuntimeException("Supported user must be an artist");
            }
            
            // Artists cannot support themselves
            if (supportedArtistId.equals(savedUser.getUserId())) {
                throw new RuntimeException("Cannot support yourself");
            }
 
            savedUser.setSupportedArtistId(supportedArtistId);
            userRepository.save(savedUser);
 
            Supporter supporter = Supporter.builder()
                .listener(savedUser)
                .artist(supportedArtist)
                .createdAt(LocalDateTime.now())
                .build();
            supporterRepository.save(supporter);
 
            scoreUpdateService.onSupporterAdded(supportedArtistId, savedUser.getUserId());
            System.out.println("Supporter created: " + savedUser.getUsername() + " supports " + supportedArtist.getUsername());
        }
 
        // How this person found Unis, for the growth metrics: a referral code
        // wins, then a waitlist email, then a campaign link, otherwise organic.
        String signupSource;
        if (referralTracked) {
            signupSource = "referral";
        } else if (markWaitlistConverted(savedUser.getEmail())) {
            signupSource = "waitlist";
        } else if (savedUser.getSignupCampaign() != null && !savedUser.getSignupCampaign().isBlank()) {
            signupSource = "campaign";
        } else {
            signupSource = "organic";
        }
        savedUser.setSignupSource(signupSource);
        savedUser.setSignupPlatform(com.unis.util.ClientPlatform.current());
        savedUser = userRepository.save(savedUser);

        System.out.println("User registered successfully: " + savedUser.getUsername() 
            + " (Referral Code: " + savedUser.getReferralCode() + ")"
            + (newUser.getDateOfBirth() != null ? " (DOB: " + newUser.getDateOfBirth() + ")" : "")
            + " (Explicit content: " + savedUser.getExplicitContentEnabled() + ")");
        return savedUser;
    }
 

    /**
     * If this email is on the waitlist, mark that entry converted and say so.
     * The waitlist's "converted" flag was never set before; signups through
     * the waitlist now count as waitlist conversions in the growth metrics.
     */
    private boolean markWaitlistConverted(String email) {
        if (email == null || email.isBlank()) return false;
        int updated = jdbcTemplate.update(
            "UPDATE pre_registrations SET converted = TRUE, converted_at = NOW() "
          + "WHERE lower(email) = lower(?) AND (converted IS NULL OR converted = FALSE)",
            email.trim());
        if (updated > 0) return true;
        Boolean already = jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pre_registrations WHERE lower(email) = lower(?))",
            Boolean.class, email.trim());
        return Boolean.TRUE.equals(already);
    }

    // CACHED: Fetch user profile (5 min TTL via "userProfiles" cache)
    @Cacheable(value = "userProfiles", key = "#userId")
    public User getProfile(UUID userId) {
        Optional<User> optionalUser = userRepository.findByIdWithJurisdiction(userId);
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        
        // Populate default song if artist
        if ("artist".equals(user.getRole().toString()) && user.getDefaultSongId() != null) {
            songRepository.findById(user.getDefaultSongId()).ifPresent(user::setDefaultSong);
        }
        
        // Force load jurisdiction to avoid lazy load issues
        if (user.getJurisdiction() != null) {
            user.getJurisdiction().getName();
        }
        
        return user;
    }

    // Update photo - EVICTS user profile cache
    @CacheEvict(value = {"userProfiles", "artists", "profileSummaries"}, key = "#userId")
    public User updatePhoto(UUID userId, String photoUrl) {
        Optional<User> optionalUser = userRepository.findById(userId);
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        user.setPhotoUrl(photoUrl);
        return userRepository.save(user);
    }

    // Update bio - EVICTS user profile cache
    @CacheEvict(value = {"userProfiles", "artists", "profileSummaries"}, key = "#userId")
    public User updateBio(UUID userId, String bio) {
        Optional<User> optionalUser = userRepository.findById(userId);
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        user.setBio(bio);
        return userRepository.save(user);
    }

    // Update default song - EVICTS user profile and artist cache
    @CacheEvict(value = {"userProfiles", "artists", "profileSummaries"}, key = "#userId")
    public User updateDefaultSong(UUID userId, UUID defaultSongId) {
        Optional<User> optionalUser = userRepository.findById(userId);
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        
        if (!"artist".equals(user.getRole().toString())) {
            throw new RuntimeException("Only artists can set a default song");
        }
        
        // Verify song exists and belongs to this artist
        if (defaultSongId != null) {
            Optional<Song> optionalSong = songRepository.findById(defaultSongId);
            Song song = optionalSong.orElseThrow(() -> new RuntimeException("Song not found"));
            
            if (!song.getArtist().getUserId().equals(userId)) {
                throw new RuntimeException("Song must belong to the artist");
            }
        }
        
        user.setDefaultSongId(defaultSongId);
        return userRepository.save(user);
    }

    // Update password - EVICTS user profile cache (even though password not visible)
    @CacheEvict(value = {"userProfiles", "profileSummaries"}, key = "#userId")
    public User updatePassword(UUID userId, String oldPassword, String newPassword) {
        Optional<User> optionalUser = userRepository.findById(userId);
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new RuntimeException("Old password incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        return userRepository.save(user);
    }

    // CACHED: Artist profile page (5 min TTL via "artists" cache)
    @Cacheable(value = "artists", key = "'profile-' + #artistId")
    public User getArtistProfile(UUID artistId) {
        Optional<User> optionalArtist = userRepository.findByIdWithJurisdiction(artistId);
        User artist = optionalArtist.orElseThrow(() -> new RuntimeException("Artist not found"));
        if (artist.getDeletedAt() != null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "This account no longer exists");
        }
        if (!"artist".equals(artist.getRole().toString())) {
            throw new RuntimeException("Not an artist");
        }
        
        // Populate default song
        if (artist.getDefaultSongId() != null) {
            songRepository.findById(artist.getDefaultSongId()).ifPresent(artist::setDefaultSong);
        }
        
        return artist;
    }

    // NOT CACHED - Used for authentication, needs fresh data
    public User findByEmail(String email) {
        return userRepository.findActiveByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found with email: " + email));
    }

    // CACHED: Top artists by jurisdiction (5 min TTL)
    // Expensive query with joins and aggregations
    @Cacheable(value = "artists", key = "'top-' + #jurisdictionId + '-' + #limit")
    public List<User> getTopArtistsByJurisdiction(UUID jurisdictionId, int limit) {
        String query = """
            WITH RECURSIVE jurisdiction_hierarchy AS (
            SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
            UNION ALL
            SELECT j.jurisdiction_id FROM jurisdictions j
            INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
            )
            SELECT DISTINCT u.user_id, u.username, u.default_song_id, COALESCE(SUM(s.score), 0) + COALESCE(SUM(v.score), 0) as total_score
            FROM users u
            LEFT JOIN songs s ON s.artist_id = u.user_id AND s.deleted_at IS NULL
                AND s.jurisdiction_id IN (SELECT jurisdiction_id FROM jurisdiction_hierarchy)
            LEFT JOIN videos v ON v.artist_id = u.user_id AND v.jurisdiction_id IN (SELECT jurisdiction_id FROM jurisdiction_hierarchy)
            WHERE u.deleted_at IS NULL
            GROUP BY u.user_id, u.username, u.default_song_id
            HAVING COALESCE(SUM(s.score), 0) + COALESCE(SUM(v.score), 0) > 0
            ORDER BY total_score DESC
            LIMIT :limit
            """;
        
        Query q = entityManager.createNativeQuery(query);
        q.setParameter("jurisdictionId", jurisdictionId);
        q.setParameter("limit", limit);
        
        @SuppressWarnings("unchecked")
        List<Object[]> results = q.getResultList();
        return results.stream()
                .map(row -> {
                    User artist = new User();
                    artist.setUserId((UUID) row[0]);
                    artist.setUsername((String) row[1]);
                    UUID defaultSongId = (UUID) row[2];
                    artist.setDefaultSongId(defaultSongId);
                    
                    // Populate default song if exists
                    if (defaultSongId != null) {
                        songRepository.findById(defaultSongId).ifPresent(artist::setDefaultSong);
                    }
                    
                    return artist;
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // ACCOUNT DELETION
    //
    // The rules (also in the help center):
    //  - Money first: no deletion while a payout is on its way; a balance of
    //    $50 or more must be cashed out first; a smaller balance can only be
    //    given up with an explicit confirmation. Earnings records are kept.
    //  - Songs are removed (kept in award history), videos deleted.
    //  - Votes and plays stay: they count for races that closed before the
    //    deletion and drop out of races still open (AwardService).
    //  - Likes, follows, playlist follows, comments, playlists, pending
    //    suggestions and votes on open suggestions leave with the account.
    //  - Fans of a deleted artist: a queued switch takes effect now, otherwise
    //    their next pick is immediate.
    //  - Joined less than 30 days ago: the sign-up rewards this account gave
    //    others (referrer bonus, supported artist's +5) are taken back.
    //  - Contact and profile details are wiped. The username stays reserved
    //    forever; the email and phone are freed.
    // =========================================================================

    /** How long after joining a deleted account's sign-up rewards are taken back. */
    static final int SIGNUP_REWARD_HOLD_DAYS = 30;

    /** A deletion refused for a reason the user can fix (code tells the app which). */
    public static class AccountDeletionBlockedException extends RuntimeException {
        private final String code;
        private final java.math.BigDecimal availableBalance;

        public AccountDeletionBlockedException(String code, String message, java.math.BigDecimal availableBalance) {
            super(message);
            this.code = code;
            this.availableBalance = availableBalance;
        }

        public String getCode() { return code; }
        public java.math.BigDecimal getAvailableBalance() { return availableBalance; }
    }

    /**
     * What the delete-account wizard needs to show before anything happens:
     * the balance, and whether deleting is blocked or needs confirmation.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getDeletionCheck(UUID userId) {
        java.math.BigDecimal available = earningsService.getAvailableBalance(userId);
        java.math.BigDecimal shown = available.max(java.math.BigDecimal.ZERO).setScale(2, java.math.RoundingMode.FLOOR);
        boolean payoutProcessing = payoutRepository.hasActivePayout(userId);
        boolean mustCashOut = available.compareTo(EarningsService.PAYOUT_THRESHOLD) >= 0;
        boolean mustConfirmForfeit = !mustCashOut && shown.signum() > 0;

        Map<String, Object> check = new java.util.LinkedHashMap<>();
        check.put("availableBalance", shown);
        check.put("payoutThreshold", EarningsService.PAYOUT_THRESHOLD);
        check.put("payoutProcessing", payoutProcessing);
        check.put("mustCashOut", mustCashOut);
        check.put("mustConfirmForfeit", mustConfirmForfeit);
        check.put("canDelete", !payoutProcessing && !mustCashOut);
        return check;
    }

    /**
     * The user deletes their own account (DELETE /api/v1/users/me).
     *
     * @param forfeitConfirmed the user ticked "I give up my $X.XX" in the wizard.
     *        Required only when they have a balance under $50.
     */
    @Transactional
    @CacheEvict(value = {"userProfiles", "artists", "profileSummaries", "jurisdictions", "leaderboards", "nominees", "playlists"}, allEntries = true)
    public void deleteAccount(UUID userId, boolean forfeitConfirmed) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        if (user.getDeletedAt() != null) {
            throw new RuntimeException("User is already deleted");
        }

        // ---- Money first: nothing is lost or stranded ----
        if (payoutRepository.hasActivePayout(userId)) {
            throw new AccountDeletionBlockedException("PAYOUT_PROCESSING",
                "A payout is still on its way to your bank. You can delete your account once it finishes.",
                null);
        }
        java.math.BigDecimal available = earningsService.getAvailableBalance(userId);
        java.math.BigDecimal shown = available.max(java.math.BigDecimal.ZERO).setScale(2, java.math.RoundingMode.FLOOR);
        if (available.compareTo(EarningsService.PAYOUT_THRESHOLD) >= 0) {
            throw new AccountDeletionBlockedException("BALANCE_MUST_BE_CASHED_OUT",
                "You have $" + shown + " in earnings. Cash it out from your Earnings page before deleting your account.",
                shown);
        }
        if (shown.signum() > 0 && !forfeitConfirmed) {
            throw new AccountDeletionBlockedException("BALANCE_FORFEIT_REQUIRED",
                "You have $" + shown + " in earnings, which is under the $50 payout minimum. "
                    + "Confirm that you give it up to delete your account.",
                shown);
        }

        purgeAccount(user, "self");
        System.out.println("[AccountDelete] action=delete status=ok userId=" + userId
            + " by=self forfeited=$" + shown);
    }

    /**
     * An admin deletes an account (AdminUserService.permanentlyDeleteUser).
     * Same cleanup as a self-deletion. No balance check: a removal for cause
     * forfeits earnings under Terms 11.6. The forfeited amount is logged.
     */
    @Transactional
    @CacheEvict(value = {"userProfiles", "artists", "profileSummaries", "jurisdictions", "leaderboards", "nominees", "playlists"}, allEntries = true)
    public java.math.BigDecimal deleteAccountByAdmin(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        if (user.getDeletedAt() != null) {
            throw new RuntimeException("User is already deleted");
        }
        java.math.BigDecimal shown = earningsService.getAvailableBalance(userId)
            .max(java.math.BigDecimal.ZERO).setScale(2, java.math.RoundingMode.FLOOR);
        purgeAccount(user, "admin");
        System.out.println("[AccountDelete] action=delete status=ok userId=" + userId
            + " by=admin forfeited=$" + shown);
        return shown;
    }

    /** The cleanup itself. Runs inside the caller's transaction. */
    private void purgeAccount(User user, String actor) {
        UUID userId = user.getUserId();
        LocalDateTime now = LocalDateTime.now();

        // 1. Content. Songs are removed (not erased) so award history keeps
        //    them; their files are hidden and purged after 30 days. Videos go.
        for (Song song : songRepository.findByArtistId(userId)) {
            mediaService.removeSong(song, "account_deleted");
        }
        for (com.unis.entity.Video video : videoRepository.findByArtistId(userId)) {
            mediaService.deleteVideo(video);
        }

        // 2. Votes, plays and ad views are KEPT. Votes and plays still count
        //    for races that closed before today; ad views are other people's
        //    earnings records (supported artist, referrers) and must never be
        //    deleted. (Deleting them used to shrink those people's balances.)

        // 3. Fans of this artist. A switch they already queued takes effect
        //    now; otherwise they're cleared and their next pick is immediate.
        for (User fan : userRepository.findBySupportedArtistId(userId)) {
            UUID pendingId = fan.getPendingSupportedArtistId();
            User next = pendingId == null || pendingId.equals(userId) ? null
                : userRepository.findById(pendingId)
                    .filter(a -> a.getDeletedAt() == null && "artist".equals(a.getRole().toString()))
                    .orElse(null);
            fan.setSupportedArtistId(next != null ? next.getUserId() : null);
            fan.setPendingSupportedArtistId(null);
            fan.setPendingSupportedArtistSince(null);
            userRepository.save(fan);
            if (next != null && !supporterRepository.existsByListenerAndArtist(fan, next)) {
                supporterRepository.save(Supporter.builder()
                    .listener(fan)
                    .artist(next)
                    .createdAt(now)
                    .build());
                scoreUpdateService.onSupporterAdded(next.getUserId(), fan.getUserId());
            }
        }
        entityManager.flush();
        // Fans who had queued a switch TO this artist keep their current one.
        jdbcTemplate.update(
            "UPDATE users SET pending_supported_artist_id = NULL, pending_supported_artist_since = NULL "
                + "WHERE pending_supported_artist_id = ?", userId);
        supporterRepository.deleteByArtistUserId(userId);

        // 4. This account as a fan.
        supporterRepository.deleteByListenerUserId(userId);

        // 5. Likes and follows are current opinions: they leave with the account
        //    (points already earned from them stay, same as unliking).
        likeRepository.deleteByUserUserId(userId);
        //    Photo likes: the soft delete keeps the users row, so the ON DELETE
        //    CASCADE on photo_likes never fires.
        jdbcTemplate.update("DELETE FROM photo_likes WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM follows WHERE follower_id = ? OR followed_id = ?", userId, userId);
        jdbcTemplate.update("""
            UPDATE playlist SET follower_count = GREATEST(0, follower_count - 1)
             WHERE playlist_id IN (SELECT playlist_id FROM playlist_follows WHERE user_id = ?)
            """, userId);
        jdbcTemplate.update("DELETE FROM playlist_follows WHERE user_id = ?", userId);

        // 6. Their playlists, comments and pending suggestions are removed.
        jdbcTemplate.update(
            "UPDATE playlist SET deleted_at = ? WHERE created_by = ? AND deleted_at IS NULL", now, userId);
        jdbcTemplate.update(
            "UPDATE comments SET deleted_at = ? WHERE user_id = ? AND deleted_at IS NULL", now, userId);
        jdbcTemplate.update(
            "UPDATE playlist_items SET status = 'removed' WHERE added_by_user_id = ? AND status = 'pending'", userId);

        // 7. Their votes come off community suggestions that are still open,
        //    and those suggestions' tallies are recounted.
        List<UUID> openItems = jdbcTemplate.queryForList("""
            SELECT pv.playlist_item_id FROM playlist_votes pv
              JOIN playlist_items pi ON pi.playlist_item_id = pv.playlist_item_id
             WHERE pv.user_id = ? AND pi.status = 'pending'
            """, UUID.class, userId);
        for (UUID itemId : openItems) {
            jdbcTemplate.update("DELETE FROM playlist_votes WHERE playlist_item_id = ? AND user_id = ?", itemId, userId);
            jdbcTemplate.update("""
                UPDATE playlist_items pi SET
                    upvotes   = (SELECT COUNT(*) FROM playlist_votes pv WHERE pv.playlist_item_id = pi.playlist_item_id AND pv.vote_type = 'up'),
                    downvotes = (SELECT COUNT(*) FROM playlist_votes pv WHERE pv.playlist_item_id = pi.playlist_item_id AND pv.vote_type = 'down')
                 WHERE pi.playlist_item_id = ?
                """, itemId);
        }

        // 8. Joined less than 30 days ago: take back the sign-up rewards this
        //    account gave others. Otherwise "join with a code, delete, join
        //    again with the same email" pays the referrer (and the supported
        //    artist) every time.
        if (user.getCreatedAt() != null && user.getCreatedAt().isAfter(now.minusDays(SIGNUP_REWARD_HOLD_DAYS))) {
            int referral = scoreUpdateService.takeBackRewardsFor("referral:" + userId);
            int supporter = scoreUpdateService.takeBackRewardsFor("supporter:" + userId);
            System.out.println("[AccountDelete] action=take_back_signup_rewards userId=" + userId
                + " referral=" + referral + " supporter=" + supporter);
        }

        // 9. Wipe contact and profile details. The username stays (reserved
        //    forever, and past awards still show it). The email and phone are
        //    freed, and the old login token stops working because it names the
        //    old email.
        user.setEmail("deleted-" + userId + "@deleted.unis.invalid");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setPhoneNumber(null);
        user.setPhoneVerified(false);
        user.setBio(null);
        user.setPhotoUrl(null);
        user.setInstagramUrl(null);
        user.setTwitterUrl(null);
        user.setTiktokUrl(null);
        user.setYoutubeUrl(null);
        user.setContactEmail(null);
        user.setGender(null);
        user.setDateOfBirth(null);
        user.setEmailNotifications(false);
        user.setSupportedArtistId(null);
        user.setPendingSupportedArtistId(null);
        user.setPendingSupportedArtistSince(null);
        user.setDefaultSongId(null);
        user.setDeletedAt(now);
        userRepository.save(user);

        System.out.println("[AccountDelete] action=purge status=ok userId=" + userId + " by=" + actor);
    }

    /**
     * Check if a user is deleted (soft delete check)
     */
    public boolean isUserDeleted(UUID userId) {
        return userRepository.findById(userId)
            .map(user -> user.getDeletedAt() != null)
            .orElse(true); // Non-existent user is considered "deleted"
    }

    /**
     * Get user only if active (not soft-deleted)
     */
    public Optional<User> getActiveUser(UUID userId) {
        return userRepository.findById(userId)
            .filter(user -> user.getDeletedAt() == null);
    }



    // First pick is immediate (no attribution history to protect). A change to an
    // existing pick is QUEUED to month-end -- the scheduler promotes it. Overwrite
    // semantics: calling again before promotion just replaces the pending target.
    @CacheEvict(value = {"userProfiles", "profileSummaries"}, key = "#userId")
    public java.util.Map<String, Object> setSupportedArtist(UUID userId, UUID newArtistId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (newArtistId == null) {
            throw new RuntimeException("Artist id is required");
        }
        if (newArtistId.equals(userId)) {
            throw new RuntimeException("Cannot support yourself");
        }

        User newArtist = userRepository.findById(newArtistId)
                .orElseThrow(() -> new RuntimeException("Artist not found"));
        if (newArtist.getDeletedAt() != null || !"artist".equals(newArtist.getRole().toString())) {
            throw new RuntimeException("Supported user must be an artist");
        }

        UUID currentId = user.getSupportedArtistId();
        java.util.Map<String, Object> result = new java.util.HashMap<>();

        // ---- First-ever pick: take effect now ----
        if (currentId == null) {
            user.setSupportedArtistId(newArtistId);
            user.setPendingSupportedArtistId(null);
            user.setPendingSupportedArtistSince(null);
            userRepository.save(user);

            Supporter supporter = Supporter.builder()
                    .listener(user)
                    .artist(newArtist)
                    .createdAt(LocalDateTime.now())
                    .build();
            supporterRepository.save(supporter);
            scoreUpdateService.onSupporterAdded(newArtistId, userId);   // +5 once per fan, ever

            System.out.println("[Support] action=set userId=" + userId
                + " status=ok mode=immediate artist=" + newArtistId);
            result.put("status", "immediate");
            result.put("effectiveArtistId", newArtistId);
            return result;
        }

        // ---- Re-selecting the current artist: cancel any queued change ----
        if (newArtistId.equals(currentId)) {
            user.setPendingSupportedArtistId(null);
            user.setPendingSupportedArtistSince(null);
            userRepository.save(user);
            System.out.println("[Support] action=set userId=" + userId
                + " status=ok mode=cancel_pending artist=" + newArtistId);
            result.put("status", "cancelled");
            result.put("effectiveArtistId", currentId);
            return result;
        }

        // ---- Change: queue to month-end (overwrite any prior pending) ----
        user.setPendingSupportedArtistId(newArtistId);
        user.setPendingSupportedArtistSince(LocalDateTime.now());
        userRepository.save(user);

        LocalDateTime effective = java.time.LocalDate.now(java.time.ZoneId.of("America/New_York"))
                .plusMonths(1).withDayOfMonth(1).atStartOfDay();

        System.out.println("[Support] action=set userId=" + userId
            + " status=ok mode=pending from=" + currentId + " to=" + newArtistId);
        result.put("status", "pending");
        result.put("effectiveArtistId", currentId);
        result.put("pendingArtistId", newArtistId);
        result.put("effectiveDate", effective.toString());
        return result;
    }

    @CacheEvict(value = {"userProfiles", "profileSummaries"}, key = "#userId")
    public void cancelPendingSupportedArtist(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setPendingSupportedArtistId(null);
        user.setPendingSupportedArtistSince(null);
        userRepository.save(user);
        System.out.println("[Support] action=cancel_pending userId=" + userId + " status=ok");
    }
    /**
     * Get total plays across all of an artist's songs
     * Uses the song_plays table to count actual plays
     * 
     * @param artistId The artist's user ID
     * @return Total number of plays
     */
    public int getTotalPlaysForArtist(UUID artistId) {
        String sql = """
            SELECT COALESCE(COUNT(sp.play_id), 0) as total_plays
            FROM song_plays sp
            JOIN songs s ON sp.song_id = s.song_id
            WHERE s.artist_id = ?
        """;
        
        Integer totalPlays = jdbcTemplate.queryForObject(sql, Integer.class, artistId);
        return totalPlays != null ? totalPlays : 0;
    }
    
    /**
     * Get total votes (score sum) for all of an artist's songs
     * Note: In your schema, votes have target_type and target_id
     * where target_type can be 'artist' or 'song'
     * 
     * @param artistId The artist's user ID
     * @return Total vote score
     */
    public int getTotalVotesForArtist(UUID artistId) {
        // Get votes directly for the artist
        String artistVotesSql = """
            SELECT COALESCE(COUNT(*), 0)
            FROM votes
            WHERE target_id = ? AND target_type = 'artist'
        """;
        
        Integer artistVotes = jdbcTemplate.queryForObject(artistVotesSql, Integer.class, artistId);
        
        // Get votes for all their songs
        String songVotesSql = """
            SELECT COALESCE(COUNT(*), 0)
            FROM votes v
            JOIN songs s ON v.target_id = s.song_id
            WHERE s.artist_id = ? AND v.target_type = 'song'
        """;
        
        Integer songVotes = jdbcTemplate.queryForObject(songVotesSql, Integer.class, artistId);
        
        return (artistVotes != null ? artistVotes : 0) + (songVotes != null ? songVotes : 0);
    }
    
    /**
     * Get total likes across all of an artist's songs
     * 
     * @param artistId The artist's user ID
     * @return Total number of likes
     */
    public int getTotalLikesForArtist(UUID artistId) {
        String sql = """
            SELECT COALESCE(COUNT(l.like_id), 0) as total_likes
            FROM likes l
            JOIN songs s ON l.media_id = s.song_id
            WHERE s.artist_id = ? AND l.media_type = 'song'
        """;
        
        Integer totalLikes = jdbcTemplate.queryForObject(sql, Integer.class, artistId);
        return totalLikes != null ? totalLikes : 0;
    }

            /**
         * Sanitize a social URL: accept null/blank (means "clear it"), accept
         * http(s)://, reject everything else (javascript:, data:, file:, etc.).
         * Returns the cleaned value or throws if dangerous.
 */
    private String sanitizeSocialUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        String lower = trimmed.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new RuntimeException("Social URL must start with http:// or https://");
        }
        // Reject obvious scheme smuggling
        if (lower.contains("javascript:") || lower.contains("data:") || lower.contains("vbscript:")) {
            throw new RuntimeException("Invalid URL");
        }
        return trimmed;
    }

    @CacheEvict(value = {"userProfiles", "artists", "profileSummaries"}, key = "#userId")
    public void updateSocialLinks(UUID userId, Map<String, String> payload) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (payload.containsKey("instagramUrl")) {
            user.setInstagramUrl(sanitizeSocialUrl(payload.get("instagramUrl")));
        }
        if (payload.containsKey("twitterUrl")) {
            user.setTwitterUrl(sanitizeSocialUrl(payload.get("twitterUrl")));
        }
        if (payload.containsKey("tiktokUrl")) {
            user.setTiktokUrl(sanitizeSocialUrl(payload.get("tiktokUrl")));
        }
        if (payload.containsKey("youtubeUrl")) {
            user.setYoutubeUrl(sanitizeSocialUrl(payload.get("youtubeUrl")));
        }
        if (payload.containsKey("contactEmail")) {
            user.setContactEmail(sanitizeContactEmail(payload.get("contactEmail")));
        }
        if (payload.containsKey("themePreference")) {
            // theme is cosmetic, no URL validation
            user.setThemePreference(payload.get("themePreference"));
        }

        

        userRepository.save(user);
        System.out.println("[SocialLinks] action=update userId=" + userId + " status=ok");
    }


    private String sanitizeContactEmail(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (!trimmed.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new RuntimeException("Enter a valid email address");
        }
        return trimmed;
    }


    @CacheEvict(value = {"userProfiles", "artists", "profileSummaries"}, key = "#userId")
    public void markPhoneVerified(UUID userId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        u.setPhoneVerified(true);
        userRepository.save(u);
    }

}