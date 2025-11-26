package com.unis.service;

import com.unis.entity.User;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import com.unis.repository.SupporterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class UserService {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SupporterRepository supporterRepository;

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

    // Register new user (mandatory supported_artist_id for listeners only)
    public User register(User newUser, UUID supportedArtistId) {
        // Hash password
        newUser.setPasswordHash(passwordEncoder.encode(newUser.getPasswordHash()));
        newUser.setCreatedAt(LocalDateTime.now());
        newUser.setScore(0);  // Init score

        // Save user
        User savedUser = userRepository.save(newUser);
        

        // For listeners: Validate and set supported artist + create Supporter
        if ("listener".equals(savedUser.getRole().toString()) && supportedArtistId != null) {
            Optional<User> optionalArtist = userRepository.findById(supportedArtistId);
            User supportedArtist = optionalArtist.orElseThrow(() -> new RuntimeException("Supported artist not found"));
            if (!"artist".equals(supportedArtist.getRole().toString())) {
                throw new RuntimeException("Supported user must be an artist");
            }
            savedUser.setSupportedArtistId(supportedArtistId);

            Supporter supporter = Supporter.builder()
                .listener(savedUser)
                .artist(supportedArtist)
                .build();
            supporterRepository.save(supporter);
        }  // Artists skip

        return savedUser;
    }

    // Fetch profile (full with jurisdiction and default song if artist)
    // In UserService.java - Update the getProfile method
    public User getProfile(UUID userId) {
        Optional<User> optionalUser = userRepository.findByIdWithJurisdiction(userId);
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        
        // Populate default song if artist
        if ("artist".equals(user.getRole().toString()) && user.getDefaultSongId() != null) {
            songRepository.findById(user.getDefaultSongId()).ifPresent(user::setDefaultSong);
        }
        
        // CRITICAL: Force load jurisdiction to avoid lazy load issues
        if (user.getJurisdiction() != null) {
            user.getJurisdiction().getName(); // Trigger lazy load
        }
        
        return user;
}

    // Update photo
    public User updatePhoto(UUID userId, String photoUrl) {
        Optional<User> optionalUser = userRepository.findById(userId);
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        user.setPhotoUrl(photoUrl);
        return userRepository.save(user);
    }

    // Update bio
    public User updateBio(UUID userId, String bio) {
        Optional<User> optionalUser = userRepository.findById(userId);
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        user.setBio(bio);
        return userRepository.save(user);
    }

    // NEW: Update default song (artists only)
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

    // Update password (validate old)
    public User updatePassword(UUID userId, String oldPassword, String newPassword) {
        Optional<User> optionalUser = userRepository.findById(userId);
        User user = optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new RuntimeException("Old password incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        return userRepository.save(user);
    }

    // Artist page fetch (user + media/awards count + default song)
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

    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found with email: " + email));
    }

    // Get top artists by total score in jurisdiction + hierarchy (for popular artists)
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
            HAVING COALESCE(SUM(s.score), 0) + COALESCE(SUM(v.score), 0) > 0  -- Only scored artists
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


        // NEW: Permanently delete the authenticated user + ALL their data
    @Transactional
    public void deleteCurrentUserAndAllData(UUID currentUserId) {
        // 1. Delete all songs by this artist (if artist)
        songRepository.deleteByArtistUserId(currentUserId);

        // 2. Delete all videos by this artist (if you ever add Video entity, it's already safe)
        videoRepository.deleteByArtistUserId(currentUserId);

        // 3. Delete all votes cast BY this user
        voteRepository.deleteByUserUserId(currentUserId);

        // 4. Delete all votes cast ON this user's songs (if artist)
        voteRepository.deleteByTargetArtistId(currentUserId);

        // 5. Delete all awards this user ever received
        awardRepository.deleteByTargetArtistId(currentUserId);

        // 6. Delete all song plays / video plays
        songPlayRepository.deleteByUserUserId(currentUserId);
        
        videoPlayRepository.deleteByUserUserId(currentUserId);

        // 7. Delete all likes (if you have a Like entity)
        likeRepository.deleteByUserUserId(currentUserId);

        // 8. Delete all ad views (if tracked per user)
        adViewRepository.deleteByUserUserId(currentUserId);

        // 9. Clean up supporter relationships
        // - If this user was a listener supporting someone → remove the link
        userRepository.nullifySupportedArtistForListeners(currentUserId);
        // - If this user was an artist being supported → remove all Supporter rows pointing to them
        supporterRepository.deleteByArtistUserId(currentUserId);
        // and the same if they were just a listener
        supporterRepository.deleteByListenerUserId(currentUserId);

        // 10. Finally delete the user record itself
        userRepository.deleteById(currentUserId);
    }
}