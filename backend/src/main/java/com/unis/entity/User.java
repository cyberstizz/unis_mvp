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

    // Never serialized. /api/v1/users/profile/{id} is public and returns this
    // entity, so without this every account's bcrypt hash was readable by anyone
    // who had a user id (they're in every profile URL). Server code reads the
    // field through the getter as before.
    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.ToString.Exclude
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

    // Never serialized (public profile endpoint). The app reads
    // stripeOnboardingComplete, never the account id itself.
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "stripe_account_id")
    private String stripeAccountId;

    // Never serialized: /api/v1/users/profile/{id} is public and returns this
    // entity, and the signup wizard promises DOB is "never shown publicly".
    // Leaving it in the JSON also told anyone exactly which accounts are minors,
    // defeating the under-18 photo safeguard. Server code reads it directly.
    @com.fasterxml.jackson.annotation.JsonIgnore
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

    @Column(name = "phone_number")
    @com.fasterxml.jackson.annotation.JsonIgnore   
    private String phoneNumber;

    @Column(name = "phone_verified", nullable = false)
    @Builder.Default
    private Boolean phoneVerified = false;         

    @Builder.Default
    @Column(name = "theme_preference", length = 20)
    private String themePreference = "blue";

    /**
     * Legacy single switch, still read by the mobile build. Kept in step with
     * messagePrivacy (EVERYONE ⇔ true) by MessagingService.
     */
    @Builder.Default
    @Column(name = "allow_messages_from_anyone", nullable = false)
    private Boolean allowMessagesFromAnyone = true;

    // -----------------------------------------------------------------------
    // Message settings (sql/V2026_10f__messaging_lanes.sql). Private: shown
    // only to the account itself and staff (UserPrivacyJacksonConfig).
    // -----------------------------------------------------------------------

    /** Who can start a new conversation with this user. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "message_privacy", nullable = false, length = 20)
    private MessagePrivacy messagePrivacy = MessagePrivacy.EVERYONE;

    /** People who support this user skip Requests and land in the inbox. */
    @Builder.Default
    @Column(name = "supporters_skip_requests", nullable = false)
    private Boolean supportersSkipRequests = true;

    /** Mutual: off hides your Active now and hides everyone else's from you. */
    @Builder.Default
    @Column(name = "show_activity_status", nullable = false)
    private Boolean showActivityStatus = true;

    /** Mutual: off sends no Seen marks and shows you none. */
    @Builder.Default
    @Column(name = "send_read_receipts", nullable = false)
    private Boolean sendReadReceipts = true;

    /** Bulk / low-quality requests go to Filtered instead of Requests. */
    @Builder.Default
    @Column(name = "filter_spam_requests", nullable = false)
    private Boolean filterSpamRequests = true;

    // -----------------------------------------------------------------------
    // Metrics (sql/V2026_10d__metrics.sql). Never serialized: this entity is
    // returned by public profile endpoints, and none of this is public.
    // The admin user-detail endpoint sends excludeFromMetrics explicitly.
    // -----------------------------------------------------------------------

    /** Staff or test account: left out of every metric. Set from the admin user page. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Builder.Default
    @Column(name = "exclude_from_metrics", nullable = false)
    private Boolean excludeFromMetrics = false;

    /** referral | waitlist | campaign | organic, decided at signup. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "signup_source", length = 20)
    private String signupSource;

    /** The campaign code from the link the person arrived on, if any. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "signup_campaign", length = 80)
    private String signupCampaign;

    /** web | ios | android, the platform the account was created on. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "signup_platform", length = 10)
    private String signupPlatform;

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
    // Never serialized: this token IS the auth for one-click unsubscribe, and the
    // public profile endpoint returns this entity. It only belongs in emails.
    @com.fasterxml.jackson.annotation.JsonIgnore
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