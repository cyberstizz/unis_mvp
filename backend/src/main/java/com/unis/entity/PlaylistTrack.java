package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "playlist_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlaylistTrack {

    @Id
    @GeneratedValue
    @Column(name = "playlist_item_id", columnDefinition = "UUID")
    private UUID playlistItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "playlist_id", referencedColumnName = "playlist_id")
    private Playlist playlist;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "song_id", columnDefinition = "UUID")
    private Song song;

    @Column(name = "position")
    private Integer position;

    @Column(name = "added_at")
    @Builder.Default
    private LocalDateTime addedAt = LocalDateTime.now();

    // --- V2 additions ---

    /**
     * The user who added or suggested this track.
     * For personal playlists: always the playlist owner.
     * For community playlists: the user who suggested the song.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by_user_id")
    private User addedBy;

    /**
     * Community playlists only: cached upvote count.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer upvotes = 0;

    /**
     * Community playlists only: cached downvote count.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer downvotes = 0;

    /**
     * active  = song is in the playlist
     * pending = suggested but not yet approved by community votes
     * removed = voted out or removed by curator
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "active";

    // --- End V2 additions ---

    // --- Helper methods ---

    public int getNetVotes() {
        return upvotes - downvotes;
    }

    public boolean isPending() {
        return "pending".equals(this.status);
    }

    public boolean isActive() {
        return "active".equals(this.status);
    }
}