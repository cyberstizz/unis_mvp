package com.unis.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data  // Lombok for getters/setters
public class UserDto {
    private String username;
    private String email;
    private String password;  // Plain for register (hashed in service)
    private String oldPassword;  // For password update
    private String role;  // "listener" or "artist"
    private UUID jurisdictionId;
    private UUID supportedArtistId;  
    private String photoUrl;  // For photo update
    private String bio;  // For bio update
    private String newPassword;  // For password update
    private UUID genreId;  // for artist registration
    private UUID defaultSongId;  // Default song  when clicking "play" on artist card)
    private String referralCode;
    private LocalDate dateOfBirth;
    private String themePreference;
    private String gender;   
}