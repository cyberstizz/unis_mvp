package com.unis.service;

import com.unis.entity.User;
import com.unis.entity.Supporter;
import com.unis.repository.UserRepository;

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
        }  // Artists skip (no supported, no Supporter)

        return savedUser;
    }

    // Fetch profile (full with jurisdiction)
    public User getProfile(UUID userId) {
        Optional<User> optionalUser = userRepository.findByIdWithJurisdiction(userId);
        return optionalUser.orElseThrow(() -> new RuntimeException("User not found"));
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

    // Artist page fetch (user + media/awards count)
    public User getArtistProfile(UUID artistId) {
        Optional<User> optionalArtist = userRepository.findByIdWithJurisdiction(artistId);
        User artist = optionalArtist.orElseThrow(() -> new RuntimeException("Artist not found"));
        if (!"artist".equals(artist.getRole().toString())) {
            throw new RuntimeException("Not an artist");
        }
        // Add counts if needed (e.g., via repo queries)
        // artist.setSupporterCount(supporterRepository.countByArtist(artist));
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
            SELECT DISTINCT u.user_id, u.username, COALESCE(SUM(s.score), 0) + COALESCE(SUM(v.score), 0) as total_score
            FROM users u
            LEFT JOIN songs s ON s.artist_id = u.user_id AND s.jurisdiction_id IN (SELECT jurisdiction_id FROM jurisdiction_hierarchy)
            LEFT JOIN videos v ON v.artist_id = u.user_id AND v.jurisdiction_id IN (SELECT jurisdiction_id FROM jurisdiction_hierarchy)
            GROUP BY u.user_id, u.username
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
                    // Optional: artist.setTotalScore(((Number) row[2]).intValue()); if adding field
                    return artist;
                })
                .collect(Collectors.toList());
    }
   
}
