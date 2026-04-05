package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "playlist")
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Playlist {

    @Id
    @GeneratedValue
    @Column(name = "playlist_id", columnDefinition = "UUID")
    private UUID playlistId;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User user;

    // --- V2 additions ---

    /**
     * personal  = user-created private collection (default)
     * community = jurisdiction-scoped, multi-contributor, vote-driven
     * official  = admin-curated or auto-populated from awards
     * auto      = system-generated personalized playlists (future)
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String type = "personal";

    /**
     * private  = only creator can see
     * unlisted = accessible via direct link but not in search/browse
     * public   = discoverable by all users
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String visibility = "private";

    @Column(length = 500)
    private String description;

    @Column(name = "cover_image_url", length = 512)
    private String coverImageUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "jurisdiction_id")
    private Jurisdiction jurisdiction;

    @Column(name = "max_songs", nullable = false)
    @Builder.Default
    private Integer maxSongs = 5000;

    @Column(name = "is_auto_populated", nullable = false)
    @Builder.Default
    private Boolean isAutoPopulated = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "follower_count", nullable = false)
    @Builder.Default
    private Integer followerCount = 0;

    @Column(name = "song_count", nullable = false)
    @Builder.Default
    private Integer songCount = 0;

    // --- End V2 additions ---

    @OneToMany(mappedBy = "playlist", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @Builder.Default
    private List<PlaylistTrack> items = new ArrayList<>();

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    // --- Helper methods ---

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isOwner(UUID userId) {
        return this.user != null && this.user.getUserId().equals(userId);
    }

    public boolean isCommunity() {
        return "community".equals(this.type);
    }

    public boolean isOfficial() {
        return "official".equals(this.type);
    }

    public boolean isVisibleTo(UUID viewerUserId) {
        if ("public".equals(this.visibility) || "unlisted".equals(this.visibility)) {
            return true;
        }
        // Private playlists only visible to owner
        return isOwner(viewerUserId);
    }
}