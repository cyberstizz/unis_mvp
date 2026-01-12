package com.unis.service;

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
import java.time.LocalDateTime;
import java.util.List;
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

    // Register new user - NOT CACHED (write operation)
    public User register(User newUser, UUID supportedArtistId, String referralCode) {
        // Hash password
        newUser.setPasswordHash(passwordEncoder.encode(newUser.getPasswordHash()));
        newUser.setCreatedAt(LocalDateTime.now());
        newUser.setScore(0);
        newUser.setLevel("silver");

        // Generate a referral code for this user
        String uniqueReferralCode = ReferralCodeGenerator.generateUnique(
            newUser.getUsername(),
            code -> userRepository.existsByReferralCode(code)
        );
        newUser.setReferralCode(uniqueReferralCode);

        // Save user
        User savedUser = userRepository.save(newUser);

        // Handle referral tracking
        if (referralCode != null && !referralCode.trim().isEmpty()) {
            Optional<User> referrerOpt = userRepository.findByReferralCode(referralCode);
            
            if (referrerOpt.isPresent()) {
                User referrer = referrerOpt.get();
                
                // Create referral record
                Referral referral = Referral.builder()
                        .referrer(referrer)
                        .referred(savedUser)
                        .createdAt(LocalDateTime.now())
                        .build();
                referralRepository.save(referral);
                
                // Award points to referrer (+5 for listeners, +2 for artists)
                scoreUpdateService.onReferral(referrer.getUserId());
                
                System.out.println("Referral tracked: " + referrer.getUsername() + " referred " + savedUser.getUsername());
            } else {
                System.out.println("Warning: Referral code '" + referralCode + "' not found. Proceeding without referral.");
            }
        }

        // For ALL users (listeners AND artists): Validate and set supported artist
        if (supportedArtistId != null) {
            Optional<User> optionalArtist = userRepository.findById(supportedArtistId);
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

            scoreUpdateService.onSupporterAdded(supportedArtistId);
            System.out.println("Supporter created: " + savedUser.getUsername() + " supports " + supportedArtist.getUsername());
        }

        System.out.println("User registered successfully: " + savedUser.getUsername() + " (Referral Code: " + savedUser.getReferralCode() + ")");
        return savedUser;
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
    @CacheEvict(value = {"userProfiles", "artists"}, key = "#userId")
    public User updatePhoto(UUID userId, String photoUrl) {
        Optional<User> optionalUser = userRepository.findById(userId);
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        user.setPhotoUrl(photoUrl);
        return userRepository.save(user);
    }

    // Update bio - EVICTS user profile cache
    @CacheEvict(value = {"userProfiles", "artists"}, key = "#userId")
    public User updateBio(UUID userId, String bio) {
        Optional<User> optionalUser = userRepository.findById(userId);
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        user.setBio(bio);
        return userRepository.save(user);
    }

    // Update default song - EVICTS user profile and artist cache
    @CacheEvict(value = {"userProfiles", "artists"}, key = "#userId")
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
    @CacheEvict(value = "userProfiles", key = "#userId")
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
        return userRepository.findByEmail(email)
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
            LEFT JOIN songs s ON s.artist_id = u.user_id AND s.jurisdiction_id IN (SELECT jurisdiction_id FROM jurisdiction_hierarchy)
            LEFT JOIN videos v ON v.artist_id = u.user_id AND v.jurisdiction_id IN (SELECT jurisdiction_id FROM jurisdiction_hierarchy)
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

    // Delete user - EVICTS all relevant caches
    @Transactional
    @CacheEvict(value = {"userProfiles", "artists"}, allEntries = true)
    public void deleteCurrentUserAndAllData(UUID currentUserId) {
        // First, verify user exists
        User user = userRepository.findById(currentUserId)
            .orElseThrow(() -> new RuntimeException("User not found: " + currentUserId));

        // Check if already deleted
        if (user.getDeletedAt() != null) {
            throw new RuntimeException("User is already deleted");
        }

        // 1. Delete all songs by this artist (content removal)
        songRepository.deleteByArtistUserId(currentUserId);

        // 2. Delete all videos by this artist (content removal)
        videoRepository.deleteByArtistUserId(currentUserId);

        // 3. REMOVED: Don't delete votes cast BY this user
        // voteRepository.deleteByUserUserId(currentUserId);
        // Reason: Preserves voting history for audit trail

        // 4. REMOVED: Don't delete votes cast ON this user's songs
        // voteRepository.deleteByTargetArtistId(currentUserId);
        // Reason: Preserves historical voting data for Milestones page

        // 5. REMOVED: Don't delete awards this user received
        // awardRepository.deleteByTargetArtistId(currentUserId);
        // Reason: Preserves historical award data for Milestones page

        // 6. Delete all song plays / video plays (activity logs)
        songPlayRepository.deleteByUserUserId(currentUserId);
        videoPlayRepository.deleteByUserUserId(currentUserId);

        // 7. Delete all likes (user preferences)
        likeRepository.deleteByUserUserId(currentUserId);

        // 8. Delete all ad views (activity logs)
        adViewRepository.deleteByUserUserId(currentUserId);

        // 9. Clean up supporter relationships
        userRepository.nullifySupportedArtistForListeners(currentUserId);
        supporterRepository.deleteByArtistUserId(currentUserId);
        supporterRepository.deleteByListenerUserId(currentUserId);

        // 10. SOFT DELETE: Mark user as deleted instead of removing
        user.setDeletedAt(LocalDateTime.now());
        userRepository.save(user);

        // Log the soft delete
        System.out.println("User soft-deleted: " + currentUserId + " at " + user.getDeletedAt());
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



    @Transactional
    @CacheEvict(value = "userProfiles", key = "#userId")
    public User changeSupportedArtist(UUID userId, UUID newArtistId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        if (!"listener".equals(user.getRole().toString())) {
            throw new RuntimeException("Only listeners can support artists");
        }
        
        // Verify new artist exists and is actually an artist
        User newArtist = userRepository.findById(newArtistId)
                .orElseThrow(() -> new RuntimeException("Artist not found"));
        if (!"artist".equals(newArtist.getRole().toString())) {
            throw new RuntimeException("Supported user must be an artist");
        }
        
        UUID oldArtistId = user.getSupportedArtistId();
        
        // Remove old supporter relationship
        if (oldArtistId != null) {
            supporterRepository.deleteByListenerUserIdAndArtistUserId(userId, oldArtistId);
        }
        
        // Create new supporter relationship
        Supporter newSupporter = Supporter.builder()
                .listener(user)
                .artist(newArtist)
                .createdAt(LocalDateTime.now())
                .build();
        supporterRepository.save(newSupporter);
        
        // Update user's supported artist reference
        user.setSupportedArtistId(newArtistId);
        userRepository.save(user);
        
        // Award +5 points to the new artist
        scoreUpdateService.onSupporterAdded(newArtistId);
        
        return user;
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


}