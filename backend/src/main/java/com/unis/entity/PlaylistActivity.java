package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "playlist_activity")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlaylistActivity {

    @Id
    @GeneratedValue
    @Column(name = "activity_id", columnDefinition = "UUID")
    private UUID activityId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "playlist_id", nullable = false)
    private Playlist playlist;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Action types:
     * song_added, song_removed, song_voted_up, song_voted_down,
     * song_approved, song_rejected, curator_removed,
     * playlist_created, playlist_renamed
     */
    @Column(name = "action_type", nullable = false, length = 30)
    private String actionType;

    /**
     * The song involved in the action (nullable for non-song actions
     * like playlist_created or playlist_renamed).
     */
    @Column(name = "target_song_id", columnDefinition = "UUID")
    private UUID targetSongId;

    /**
     * Optional context, e.g. "Curator removed: off-topic"
     */
    @Column(length = 500)
    private String details;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}