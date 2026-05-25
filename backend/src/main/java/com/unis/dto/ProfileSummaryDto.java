package com.unis.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Consolidated payload for the user's own profile page.
 * Replaces 5–6 separate round trips with a single request.
 *
 * Returned ONLY for the authenticated user fetching their own summary —
 * the controller enforces this with an ownership check.
 *
 * The `settings` block carries the user's preference toggles
 * (emailNotifications, publicProfile, showVoteHistory), which are now
 * real NOT NULL columns on the User entity. The frontend reads these
 * instead of falling back to client-side DEFAULTS.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileSummaryDto {

    private SelfProfile profile;
    private SupportedArtistInfo supportedArtist; // nullable
    private VoteHistorySummary voteHistory;       // nullable until VoteRepository is wired
    private String referralCode;
    private Settings settings;

    // -----------------------------------------------------------------------
    // Self-profile: what the user sees about themselves
    // -----------------------------------------------------------------------
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SelfProfile {
        private UUID userId;
        private String username;
        private String email;
        private String bio;
        private String photoUrl;
        private Integer score;
        private String level;
        private String themePreference;
        private String role;
        private UUID supportedArtistId; // nullable
        private JurisdictionInfo jurisdiction;
        private String instagramUrl;
        private String twitterUrl;
        private String tiktokUrl;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JurisdictionInfo {
        private UUID jurisdictionId;
        private String name;
    }

    // -----------------------------------------------------------------------
    // Supported artist (the artist this user supports, if any)
    // -----------------------------------------------------------------------
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupportedArtistInfo {
        private UUID userId;
        private String username;
        private String photoUrl;
        private DefaultSongInfo defaultSong; // nullable
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DefaultSongInfo {
        private UUID songId;
        private String title;
        private String fileUrl;
        private String artworkUrl;
        private Integer duration;
    }

    // -----------------------------------------------------------------------
    // Vote history summary — count + a small recent slice
    // TODO: structure is in place but service method returns null for now,
    //       pending verification of VoteRepository methods.
    // -----------------------------------------------------------------------
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VoteHistorySummary {
        private long totalCount;
        private List<RecentVote> recent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentVote {
        private UUID voteId;
        private UUID targetId;
        private String targetType;
        private String targetName;
        private LocalDateTime votedAt;
    }

    // -----------------------------------------------------------------------
    // Preference toggles — backed by real NOT NULL columns on User.
    // -----------------------------------------------------------------------
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Settings {
        private Boolean emailNotifications;
        private Boolean publicProfile;
        private Boolean showVoteHistory;
    }
}