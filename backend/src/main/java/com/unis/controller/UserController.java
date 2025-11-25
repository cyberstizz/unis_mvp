package com.unis.controller;

import com.unis.dto.UserDto;

import com.unis.entity.User;
import com.unis.entity.User.Role;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.GenreRepository;
import com.unis.repository.UserRepository;
import com.unis.repository.SongRepository;
import com.unis.entity.Genre;
import com.unis.entity.Jurisdiction;
import com.unis.entity.Song;
import com.unis.service.UserService;
import com.unis.service.FileStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;



import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;


@RestController
@RequestMapping("/api/v1/users")  // Base path
public class UserController {
    @Autowired
    private UserService userService;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private JurisdictionRepository jurisdictionRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SongRepository songRepository;

    // POST /api/v1/users/register (page 6 signup)
    @PostMapping("/register")
    public ResponseEntity<User> register(@RequestBody UserDto dto) {
        User user = User.builder()
            .username(dto.getUsername())
            .email(dto.getEmail())
            .passwordHash(dto.getPassword())
            .role(User.Role.valueOf(dto.getRole()))
            .bio(dto.getBio())
            .build();
        
        // Fetch jurisdiction entity and set
        Jurisdiction jurisdiction = jurisdictionRepository.findById(dto.getJurisdictionId())
            .orElseThrow(() -> new RuntimeException("Jurisdiction not found"));
        user.setJurisdiction(jurisdiction);
        
        // ADD THIS: Set genre for artists
        if (dto.getGenreId() != null) {
            Genre genre = genreRepository.findById(dto.getGenreId())
                .orElseThrow(() -> new RuntimeException("Genre not found"));
            user.setGenre(genre);
        }
        
        User registered = userService.register(user, dto.getSupportedArtistId());
        return ResponseEntity.ok(registered);
    }

    // GET /api/v1/users/profile/{id} (page 6 dashboard)
   @GetMapping("/profile/{userId}")
    public ResponseEntity<User> getProfile(@PathVariable UUID userId) {
    Optional<User> optUser = userRepository.findByIdWithAssociations(userId);
    if (optUser.isEmpty()) return ResponseEntity.notFound().build();
    return ResponseEntity.ok(optUser.get());  // Full with jurisdiction/defaultSong
    }

    // PUT /api/v1/users/profile/{id}/photo (edit photo)
    @PutMapping("/profile/{userId}/photo")
    public ResponseEntity<User> updatePhoto(@PathVariable UUID userId, @RequestBody UserDto dto) {
        User updated = userService.updatePhoto(userId, dto.getPhotoUrl());
        return ResponseEntity.ok(updated);
    }

    // PUT /api/v1/users/profile/{id}/bio (edit bio)
    @PutMapping("/profile/{userId}/bio")
    public ResponseEntity<User> updateBio(@PathVariable UUID userId, @RequestBody UserDto dto) {
        User updated = userService.updateBio(userId, dto.getBio());
        return ResponseEntity.ok(updated);
    }

    // PUT /api/v1/users/profile/{id}/password (update password)
    @PutMapping("/profile/{userId}/password")
    public ResponseEntity<User> updatePassword(@PathVariable UUID userId, @RequestBody UserDto dto) {
        User updated = userService.updatePassword(userId, dto.getOldPassword(), dto.getNewPassword());
        return ResponseEntity.ok(updated);
    }

    // GET /api/v1/users/artist/{id} (page 10 artist page)
    @GetMapping("/artist/{artistId}")
    public ResponseEntity<User> getArtistProfile(@PathVariable UUID artistId) {
        User artist = userService.getArtistProfile(artistId);
        return ResponseEntity.ok(artist);
    }

    @GetMapping("/artist/top")
    public ResponseEntity<List<User>> getTopArtists(@RequestParam UUID jurisdictionId, @RequestParam(defaultValue = "5") int limit) {
        // Use native query in UserService: SUM(s.score) GROUP BY artist_id ORDER DESC (JOIN songs/videos)
        List<User> tops = userService.getTopArtistsByJurisdiction(jurisdictionId, limit);
        return ResponseEntity.ok(tops);
    }

    @GetMapping("/{userId}/default-song")
    public ResponseEntity<Song> getDefaultSong(@PathVariable UUID userId) {
    Optional<User> optUser = userRepository.findById(userId);
    if (optUser.isEmpty() || optUser.get().getDefaultSongId() == null) return ResponseEntity.notFound().build();
    Optional<Song> optSong = songRepository.findById(optUser.get().getDefaultSongId());
    return optSong.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/me")
    public ResponseEntity<Map<String, String>> deleteMyAccount(Authentication auth) {
        // FIX: Get email from token, then find user
        String email = auth.getName();  // This is the email, NOT userId!
        
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        UUID userId = user.getUserId();
        
        try {
            // Call service to cascade delete everything
            userService.deleteCurrentUserAndAllData(userId);
            
            Map<String, String> response = new HashMap<>();
            response.put("message", "Account deleted successfully");
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            // Log the actual error
            System.err.println("Failed to delete user " + userId + ": " + e.getMessage());
            e.printStackTrace();
            
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to delete account: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    @GetMapping("/artists/active")
    public ResponseEntity<List<User>> getActiveArtists() {
    List<User> artists = userRepository.findByRoleOrderByScoreDesc(User.Role.artist);
    return ResponseEntity.ok(artists);
}

        // TEMP endpoint for CreateAccountWizard photo upload (anonymous allowed)
   @PatchMapping("/profile/photo")
    public ResponseEntity<Map<String, String>> uploadSignupPhoto(
                HttpServletRequest request,
                @RequestParam("photo") MultipartFile file) throws IOException {

            // Save file (reuse your existing upload logic)
            String photoUrl = fileStorageService.storeFile(file);

            Map<String, String> response = new HashMap<>();
            response.put("photoUrl", photoUrl);
            return ResponseEntity.ok(response);
        }

    // PATCH /api/v1/users/profile — FINAL WORKING VERSION
    @PatchMapping("/profile")
    public ResponseEntity<Map<String, String>> updateProfile(
        Authentication auth,
        @RequestParam(value = "photo", required = false) MultipartFile photo,
        @RequestParam(value = "bio", required = false) String bio) throws IOException {

        // FIX: Get user by email from token, then get UUID
        String email = auth.getName();  // this is the email
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));

        UUID userId = user.getUserId();

        Map<String, String> response = new HashMap<>();

        if (photo != null && !photo.isEmpty()) {
            String photoUrl = fileStorageService.storeFile(photo);
            userService.updatePhoto(userId, photoUrl);
            response.put("photoUrl", photoUrl);
        }

        if (bio != null && !bio.isBlank()) {
            userService.updateBio(userId, bio.trim());
            response.put("bio", bio.trim());
        }

        response.put("message", "Profile updated successfully");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/default-song")
    public ResponseEntity<Map<String, String>> setDefaultSong(
        Authentication auth,
        @RequestBody Map<String, UUID> payload) {
        
        String email = auth.getName();
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        UUID songId = payload.get("defaultSongId");
        userService.updateDefaultSong(user.getUserId(), songId);
        
        return ResponseEntity.ok(Map.of("message", "Default song set"));
    }

}