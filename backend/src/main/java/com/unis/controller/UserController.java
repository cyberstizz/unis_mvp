package com.unis.controller;

import com.unis.dto.UserDto;
import com.unis.util.SecurityUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.extern.slf4j.Slf4j;
import com.unis.entity.User;
import com.unis.entity.User.Role;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.GenreRepository;
import com.unis.repository.UserRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.FollowRepository;
import com.unis.entity.Genre;
import com.unis.entity.Jurisdiction;
import com.unis.entity.Song;
import com.unis.service.UserService;
import com.unis.service.FileStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;



import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;


@RestController
@RequestMapping("/api/v1/users")
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

    @Autowired
    private FollowRepository followRepository;

    private static final Logger log = LoggerFactory.getLogger(UserController.class);


    // POST /api/v1/users/register (page 6 signup)
    @PostMapping("/register")
    public ResponseEntity<User> register(@RequestBody UserDto dto) {
        User user = User.builder()
            .username(dto.getUsername())
            .email(dto.getEmail())
            .passwordHash(dto.getPassword())
            .role(User.Role.valueOf(dto.getRole()))
            .bio(dto.getBio())
            .photoUrl(dto.getPhotoUrl())
            .build();

            user.setDateOfBirth(dto.getDateOfBirth());


        // Fetch jurisdiction entity and set
        Jurisdiction jurisdiction = jurisdictionRepository.findById(dto.getJurisdictionId())
            .orElseThrow(() -> new RuntimeException("Jurisdiction not found"));
        user.setJurisdiction(jurisdiction);

        // Set genre for artists
        if (dto.getGenreId() != null) {
            Genre genre = genreRepository.findById(dto.getGenreId())
                .orElseThrow(() -> new RuntimeException("Genre not found"));
            user.setGenre(genre);
        }

        // Pass referral code to register method
        User registered = userService.register(
            user,
            dto.getSupportedArtistId(),
            dto.getReferralCode()
        );

        return ResponseEntity.ok(registered);
    }

    // GET /api/v1/users/profile/{id} (page 6 dashboard)
    @GetMapping("/profile/{userId}")
    public ResponseEntity<User> getProfile(@PathVariable UUID userId) {
        Optional<User> optUser = userRepository.findByIdWithAssociations(userId);
        if (optUser.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(optUser.get());
    }

    // PUT /api/v1/users/profile/{id}/photo (edit photo)
    // C4 FIX: Ownership check — only the authenticated user can update their own photo
    @PutMapping("/profile/{userId}/photo")
    public ResponseEntity<?> updatePhoto(@PathVariable UUID userId, @RequestBody UserDto dto) {
        UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();
        if (!authenticatedUserId.equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("You can only update your own profile");
        }
        User updated = userService.updatePhoto(userId, dto.getPhotoUrl());
        return ResponseEntity.ok(updated);
    }

    // PUT /api/v1/users/profile/{id}/bio (edit bio)
    // C4 FIX: Ownership check
    @PutMapping("/profile/{userId}/bio")
    public ResponseEntity<?> updateBio(@PathVariable UUID userId, @RequestBody UserDto dto) {
        UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();
        if (!authenticatedUserId.equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("You can only update your own profile");
        }
        User updated = userService.updateBio(userId, dto.getBio());
        return ResponseEntity.ok(updated);
    }

    // PUT /api/v1/users/profile/{id} (update social media URLs)
    // C4 FIX: Ownership check
    @PutMapping("/profile/{userId}")
    public ResponseEntity<?> updateSocialMedia(
            @PathVariable UUID userId,
            @RequestBody Map<String, String> payload) {

        UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();
        if (!authenticatedUserId.equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("You can only update your own profile");
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        // Update whichever social field is provided
        if (payload.containsKey("instagramUrl")) {
            user.setInstagramUrl(payload.get("instagramUrl"));
        }
        if (payload.containsKey("twitterUrl")) {
            user.setTwitterUrl(payload.get("twitterUrl"));
        }
        if (payload.containsKey("tiktokUrl")) {
            user.setTiktokUrl(payload.get("tiktokUrl"));
        }
         if (payload.containsKey("themePreference")) {
        user.setThemePreference(payload.get("themePreference"));
        }

        userRepository.save(user);

        Map<String, String> response = new HashMap<>();
        response.put("message", "Social media updated successfully");
        return ResponseEntity.ok(response);
    }

    // PUT /api/v1/users/profile/{id}/password (update password)
    // C4 FIX: Ownership check
    @PutMapping("/profile/{userId}/password")
    public ResponseEntity<?> updatePassword(@PathVariable UUID userId, @RequestBody UserDto dto) {
        UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();
        if (!authenticatedUserId.equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("You can only update your own profile");
        }
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
        String email = auth.getName();

        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));

        UUID userId = user.getUserId();

        try {
            userService.deleteCurrentUserAndAllData(userId);

            Map<String, String> response = new HashMap<>();
            response.put("message", "Account deleted successfully");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
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

        String photoUrl = fileStorageService.storeFile(file);

        Map<String, String> response = new HashMap<>();
        response.put("photoUrl", photoUrl);
        return ResponseEntity.ok(response);
    }

    // PATCH /api/v1/users/profile — already uses Authentication correctly, no C4/C6 change needed
    @PatchMapping("/profile")
    public ResponseEntity<Map<String, String>> updateProfile(
        Authentication auth,
        @RequestParam(value = "photo", required = false) MultipartFile photo,
        @RequestParam(value = "bio", required = false) String bio) throws IOException {

        String email = auth.getName();
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


    @GetMapping("/referral-code/{userId}")
    public ResponseEntity<Map<String, String>> getReferralCode(@PathVariable UUID userId) {
        try {
            User user = userService.getProfile(userId);

            Map<String, String> response = new HashMap<>();
            response.put("username", user.getUsername());
            response.put("referralCode", user.getReferralCode());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/validate-referral/{code}")
    public ResponseEntity<Map<String, Object>> validateReferralCode(@PathVariable String code) {
        Map<String, Object> response = new HashMap<>();

        // Master launch code - always valid
        if ("UNIS-LAUNCH-2024".equals(code)) {
            response.put("valid", true);
            response.put("referrerUsername", "Unis");
            response.put("referrerId", null);
            return ResponseEntity.ok(response);
        }

        try {
            Optional<User> referrerOpt = userRepository.findByReferralCode(code);

            if (referrerOpt.isPresent()) {
                User referrer = referrerOpt.get();
                response.put("valid", true);
                response.put("referrerUsername", referrer.getUsername());
                response.put("referrerId", referrer.getUserId());
                return ResponseEntity.ok(response);
            } else {
                response.put("valid", false);
                response.put("message", "Invalid referral code");
                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            response.put("valid", false);
            response.put("message", "Error validating code");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/check-email")
    public ResponseEntity<Map<String, Object>> checkEmailAvailability(@RequestParam String email) {
        Map<String, Object> response = new HashMap<>();

        try {
            boolean exists = userRepository.existsByEmail(email);
            response.put("available", !exists);
            response.put("email", email);

            if (exists) {
                response.put("message", "Email already registered");
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("available", false);
            response.put("message", "Error checking email");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/check-username")
    public ResponseEntity<Map<String, Object>> checkUsernameAvailability(@RequestParam String username) {
        Map<String, Object> response = new HashMap<>();

        try {
            boolean exists = userRepository.existsByUsername(username);
            response.put("available", !exists);
            response.put("username", username);

            if (exists) {
                response.put("message", "Username already taken");
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("available", false);
            response.put("message", "Error checking username");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/artists/with-preview")
    public ResponseEntity<List<Map<String, Object>>> getArtistsWithPreview(
            @RequestParam(required = false) UUID jurisdictionId) {

        try {
            List<User> artists;

            if (jurisdictionId != null) {
                artists = userRepository.findByRoleAndJurisdiction(
                    User.Role.artist,
                    jurisdictionId
                );
            } else {
                artists = userRepository.findByRoleOrderByScoreDesc(User.Role.artist);
            }

            List<Map<String, Object>> result = artists.stream().map(artist -> {
                Map<String, Object> artistData = new HashMap<>();
                artistData.put("userId", artist.getUserId());
                artistData.put("username", artist.getUsername());
                artistData.put("photoUrl", artist.getPhotoUrl());
                artistData.put("bio", artist.getBio());
                artistData.put("score", artist.getScore());
                artistData.put("defaultSongId", artist.getDefaultSongId());

                if (artist.getJurisdiction() != null) {
                    Map<String, Object> jurisdiction = new HashMap<>();
                    jurisdiction.put("jurisdictionId", artist.getJurisdiction().getJurisdictionId());
                    jurisdiction.put("name", artist.getJurisdiction().getName());
                    artistData.put("jurisdiction", jurisdiction);
                }

                if (artist.getDefaultSongId() != null) {
                    songRepository.findById(artist.getDefaultSongId()).ifPresent(song -> {
                        Map<String, Object> songData = new HashMap<>();
                        songData.put("songId", song.getSongId());
                        songData.put("title", song.getTitle());
                        songData.put("fileUrl", song.getFileUrl());
                        songData.put("artworkUrl", song.getArtworkUrl());
                        songData.put("duration", song.getDuration());
                        artistData.put("defaultSong", songData);
                    });
                }

                return artistData;
            }).collect(Collectors.toList());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(List.of());
        }
    }

    @GetMapping("/{userId}/supporters/count")
    public ResponseEntity<Map<String, Long>> getSupportersCount(@PathVariable UUID userId) {
        long count = userRepository.countBySupportedArtistId(userId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @GetMapping("/{userId}/followers/count")
    public ResponseEntity<Map<String, Long>> getFollowersCount(@PathVariable UUID userId) {
        long count = followRepository.countByFollowed_UserId(userId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PostMapping("/{artistId}/follow")
    public ResponseEntity<Void> followUser(@PathVariable UUID artistId, Authentication auth) {
        String email = auth.getName();
        User currentUser = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (currentUser.getUserId().equals(artistId)) {
            return ResponseEntity.badRequest().build();
        }

        if (!followRepository.existsByFollower_UserIdAndFollowed_UserId(currentUser.getUserId(), artistId)) {
            User artistToFollow = userRepository.findById(artistId)
                .orElseThrow(() -> new RuntimeException("Artist not found"));

            com.unis.entity.Follow follow = com.unis.entity.Follow.builder()
                .follower(currentUser)
                .followed(artistToFollow)
                .build();

            followRepository.save(follow);
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{artistId}/follow")
    @jakarta.transaction.Transactional
    public ResponseEntity<Void> unfollowUser(@PathVariable UUID artistId, Authentication auth) {
        String email = auth.getName();
        User currentUser = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));

        followRepository.deleteByFollower_UserIdAndFollowed_UserId(currentUser.getUserId(), artistId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{artistId}/is-following")
    public ResponseEntity<Map<String, Boolean>> isFollowing(@PathVariable UUID artistId, Authentication auth) {
        String email = auth.getName();
        User currentUser = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));

        boolean isFollowing = followRepository.existsByFollower_UserIdAndFollowed_UserId(currentUser.getUserId(), artistId);
        return ResponseEntity.ok(Map.of("isFollowing", isFollowing));
    }

    @GetMapping("/{userId}/total-plays")
    public ResponseEntity<Map<String, Integer>> getTotalPlays(@PathVariable UUID userId) {
        try {
            int totalPlays = userService.getTotalPlaysForArtist(userId);
            return ResponseEntity.ok(Map.of("totalPlays", totalPlays));
        } catch (Exception e) {
            log.error("Failed to get total plays for artist {}: {}", userId, e.getMessage());
            return ResponseEntity.ok(Map.of("totalPlays", 0));
        }
    }

    @GetMapping("/{userId}/total-votes")
    public ResponseEntity<Map<String, Integer>> getTotalVotes(@PathVariable UUID userId) {
        try {
            int totalVotes = userService.getTotalVotesForArtist(userId);
            return ResponseEntity.ok(Map.of("totalVotes", totalVotes));
        } catch (Exception e) {
            log.error("Failed to get total votes for artist {}: {}", userId, e.getMessage());
            return ResponseEntity.ok(Map.of("totalVotes", 0));
        }
    }

    @GetMapping("/{userId}/total-likes")
    public ResponseEntity<Map<String, Integer>> getTotalLikes(@PathVariable UUID userId) {
        try {
            int totalLikes = userService.getTotalLikesForArtist(userId);
            return ResponseEntity.ok(Map.of("totalLikes", totalLikes));
        } catch (Exception e) {
            log.error("Failed to get total likes for artist {}: {}", userId, e.getMessage());
            return ResponseEntity.ok(Map.of("totalLikes", 0));
        }
    }
}