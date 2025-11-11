package com.unis.controller;

import com.unis.dto.UserDto;
import com.unis.entity.User;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.GenreRepository;
import com.unis.entity.Genre;
import com.unis.entity.Jurisdiction;  
import com.unis.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")  // Base path
public class UserController {
    @Autowired
    private UserService userService;

    @Autowired
    private JurisdictionRepository jurisdictionRepository;

    @Autowired
    private GenreRepository genreRepository;

    // POST /api/v1/users/register (page 6 signup)
    @PostMapping("/register")
    public ResponseEntity<User> register(@RequestBody UserDto dto) {
        User user = User.builder()
            .username(dto.getUsername())
            .email(dto.getEmail())
            .passwordHash(dto.getPassword())
            .role(User.Role.valueOf(dto.getRole()))
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
        User profile = userService.getProfile(userId);
        return ResponseEntity.ok(profile);
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
}