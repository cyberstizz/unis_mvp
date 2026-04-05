package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "playlist_votes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlaylistVote {

    @Id
    @GeneratedValue
    @Column(name = "vote_id", columnDefinition = "UUID")
    private UUID voteId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "playlist_item_id", nullable = false)
    private PlaylistTrack playlistItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * "up" or "down"
     */
    @Column(name = "vote_type", nullable = false, length = 10)
    private String voteType;

    @Column(name = "voted_at", nullable = false)
    @Builder.Default
    private LocalDateTime votedAt = LocalDateTime.now();
}