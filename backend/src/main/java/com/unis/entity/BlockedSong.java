package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "blocked_songs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockedSong {

    @Id
    @GeneratedValue
    @Column(name = "block_id", columnDefinition = "UUID")
    private UUID blockId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "song_id", nullable = false)
    private Song song;

    @Column(name = "blocked_at", nullable = false)
    @Builder.Default
    private LocalDateTime blockedAt = LocalDateTime.now();
}