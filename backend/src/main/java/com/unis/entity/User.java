package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
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

    @Column(name = "youtube_url")
    private String youtubeUrl;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "gender")
    private String gender;

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

    @Column(name = "stripe_account_id")
    private String stripeAccountId;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Builder.Default
    @Column(name = "explicit_content_enabled")
    private Boolean explicitContentEnabled = true;

    @Builder.Default
    @Column(name = "stripe_onboarding_complete")
    private Boolean stripeOnboardingComplete = false;

    @Builder.Default
    @Column(name = "email_verified", nullable = false)
    private Boolean emailVerified = false;   

    @Column(name = "theme_preference", length = 20)
    private String themePreference = "blue";

    // -----------------------------------------------------------------------
    // Preference flags (backing columns for AccountSettings toggles).
    // @Builder.Default is required: with @Builder, an un-set field is null,
    // and these columns are NOT NULL -- without the default, builder-created
    // users fail to insert.
    // -----------------------------------------------------------------------
    @Builder.Default
    @Column(name = "email_notifications", nullable = false)
    private Boolean emailNotifications = true;

    @Builder.Default
    @Column(name = "public_profile", nullable = false)
    private Boolean publicProfile = true;

    @Builder.Default
    @Column(name = "show_vote_history", nullable = false)
    private Boolean showVoteHistory = false;

    // Per-user token for one-click email unsubscribe (clicked from an email,
    // so it can't rely on a session -- the unguessable token is the auth).
    // updatable=false: it's assigned once at creation and never rotated here.
    @Builder.Default
    @Column(name = "unsubscribe_token", nullable = false, unique = true, updatable = false)
    private UUID unsubscribeToken = UUID.randomUUID();

    // -----------------------------------------------------------------------
    // Pending supported-artist change. The effective artist (supportedArtistId
    // above) only changes at the month boundary; a fan's mid-month change is
    // queued here and promoted by SupportedArtistScheduler on the 1st. Nullable:
    // null means "no change queued". Overwritable any number of times before
    // promotion. since-timestamp captured for the tracking/quality audit.
    // -----------------------------------------------------------------------
    @Column(name = "pending_supported_artist_id")
    private UUID pendingSupportedArtistId;

    @Column(name = "pending_supported_artist_since")
    private LocalDateTime pendingSupportedArtistSince;

    public enum Role {
        listener, artist
    }

    public boolean isDeleted() {
    return deletedAt != null;
    }
}