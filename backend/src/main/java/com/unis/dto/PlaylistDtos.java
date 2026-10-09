package com.unis.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * All playlist-related DTOs grouped together.
 */
public class PlaylistDtos {

    // ========================================================================
    // REQUEST DTOs
    // ========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreatePlaylistRequest {
        private String name;
        private String type;            // "personal" (default), "community", "official"
        private String visibility;      // "private" (default), "unlisted", "public"
        private String description;
        private UUID jurisdictionId;    // required for community playlists
        private String coverImageUrl;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdatePlaylistRequest {
        private String name;
        private String visibility;
        private String description;
        private String coverImageUrl;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddTrackRequest {
        private UUID songId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlaylistVoteRequest {
        private String voteType;        // "up" or "down"
    }

    // ========================================================================
    // RESPONSE DTOs
    // ========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlaylistResponse {
        private UUID playlistId;
        private String name;
        private String type;
        private String visibility;
        private String description;
        private String coverImageUrl;

        private UUID jurisdictionId;
        private String jurisdictionName;

        private UUID creatorId;
        private String creatorName;
        private String creatorPhotoUrl;

        private int songCount;
        private int followerCount;
        private boolean isFollowing;
        private boolean isOwner;
        // Points this playlist paid its owner (community start + 10-follower
        // milestone). Deleting the playlist takes these back. 0 for non-owners.
        private int ownerPointsEarned;

        private List<TrackResponse> tracks;

        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TrackResponse {
        private UUID playlistItemId;
        private UUID songId;
        private String title;
        private String artistName;
        private UUID artistId;
        private String artworkUrl;
        private String fileUrl;
        private Integer duration;

        private int position;
        private LocalDateTime addedAt;
        private String addedByUsername;
        private UUID addedById;           // lets the page hide voting on your own suggestion
        private String myVote;            // "up", "down", or null — pending list only

        // Community playlist fields
        private int upvotes;
        private int downvotes;
        private String status;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlaylistSummaryResponse {
        private UUID playlistId;
        private String name;
        private String type;
        private String visibility;
        private int songCount;
        private int followerCount;
        private String coverImageUrl;
        private String creatorName;
        private UUID creatorId;
        private List<String> firstFourArtworks;     // for mosaic cover fallback
        private LocalDateTime updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ActivityResponse {
        private UUID activityId;
        private String username;
        private String userPhotoUrl;
        private String actionType;
        private String songTitle;
        private UUID songId;
        private String details;
        private LocalDateTime createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BlockedSongResponse {
        private UUID songId;
        private String title;
        private String artistName;
        private String artworkUrl;
        private LocalDateTime blockedAt;
    }
}