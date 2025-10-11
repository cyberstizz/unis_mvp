package com.unis.service;

import com.unis.entity.User;
import com.unis.entity.Supporter;
import com.unis.repository.UserRepository;
import com.unis.repository.SupporterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Transactional
public class UserService {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SupporterRepository supporterRepository;  // Assume you have this repo

    @Autowired
    private PasswordEncoder passwordEncoder;

    // Register new user (mandatory supported_artist_id validation)
    public User register(User newUser, UUID supportedArtistId) {
        // Validate supported_artist_id exists and is artist role
        User supportedArtist = userRepository.findById(supportedArtistId)
            .orElseThrow(() -> new RuntimeException("Supported artist not found"));
        if (!"artist".equals(supportedArtist.getRole().toString())) {
            throw new RuntimeException("Supported user must be an artist");
        }

        // Hash password
        newUser.setPasswordHash(passwordEncoder.encode(newUser.getPasswordHash()));
        newUser.setCreatedAt(LocalDateTime.now());
        newUser.setScore(0);  // Init score

        // Save user
        User savedUser = userRepository.save(newUser);
        savedUser.setSupportedArtistId(supportedArtistId);  // Set FK

        // Auto-create supporter link
        Supporter supporter = Supporter.builder()
            .listener(savedUser)
            .artist(supportedArtist)
            .build();
        supporterRepository.save(supporter);

        return savedUser;
    }

    // Fetch profile (full with jurisdiction)
    public User getProfile(UUID userId) {
        return userRepository.findByIdWithJurisdiction(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // Update photo
    public User updatePhoto(UUID userId, String photoUrl) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        user.setPhotoUrl(photoUrl);
        return userRepository.save(user);
    }

    // Update bio
    public User updateBio(UUID userId, String bio) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        user.setBio(bio);
        return userRepository.save(user);
    }

    // Update password (validate old)
    public User updatePassword(UUID userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new RuntimeException("Old password incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        return userRepository.save(user);
    }

    // Artist page fetch (user + media/awards count)
    public User getArtistProfile(UUID artistId) {
        User artist = userRepository.findByIdWithJurisdiction(artistId)
            .orElseThrow(() -> new RuntimeException("Artist not found"));
        if (!"artist".equals(artist.getRole().toString())) {
            throw new RuntimeException("Not an artist");
        }
        // Add counts if needed (e.g., via repo queries)
        // artist.setSupporterCount(supporterRepository.countByArtist(artist));
        return artist;
    }
}