package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class User {
    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID userId;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "supported_artist_id")
    private UUID supportedArtistId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "jurisdiction_id")
    private Jurisdiction jurisdiction;

    @Builder.Default  
    @Column
    private Integer score = 0;

    @Builder.Default 
    @Column(name = "level")
    private String level = "silver";

    @Builder.Default  
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "photo_url")
    private String photoUrl;

    @Column
    private String bio;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "genre_id")
    private Genre genre;

    @Column(name = "default_song_id")
    private UUID defaultSongId;

    // Transient field to hold the actual Song object when needed (populated by service layer)
    @Transient
    private Song defaultSong;

    @Column(name = "instagram_url")
    private String instagramUrl;

    @Column(name = "twitter_url")
    private String twitterUrl;

    @Column(name = "tiktok_url")
    private String tiktokUrl;

    @Column(name = "referral_code", unique = true, nullable = false, length = 50)
    private String referralCode;

    @Builder.Default
    @Column(name = "total_plays", nullable = false)
    private Integer totalPlays = 0;

    @Builder.Default
    @Column(name = "total_votes", nullable = false)
    private Integer totalVotes = 0;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public enum Role {
        listener, artist
    }

    public boolean isDeleted() {
    return deletedAt != null;
    }
}