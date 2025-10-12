package com.unis.controller;

import com.unis.dto.UserDto;
import com.unis.entity.User;
import com.unis.repository.JurisdictionRepository;
import com.unis.entity.Jurisdiction;  // For setting jurisdiction object
import com.unis.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")  // Base path
public class UserController {
    @Autowired
    private UserService userService;

    @Autowired
    private JurisdictionRepository jurisdictionRepository;  // For fetching entity

    // POST /api/v1/users/register (page 6 signup)
    @PostMapping("/register")
    public ResponseEntity<User> register(@RequestBody UserDto dto) {
        User user = User.builder()  // Map DTO to entity
            .username(dto.getUsername())
            .email(dto.getEmail())
            .passwordHash(dto.getPassword())  // Hashed in service
            .role(User.Role.valueOf(dto.getRole()))
            .build();
        // Fetch jurisdiction entity and set
        Jurisdiction jurisdiction = jurisdictionRepository.findById(dto.getJurisdictionId())
            .orElseThrow(() -> new RuntimeException("Jurisdiction not found"));
        user.setJurisdiction(jurisdiction);
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
}