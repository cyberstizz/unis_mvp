package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "song_plays")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SongPlay {
    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID playId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "song_id")
    private Song song;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "played_at")
    private LocalDateTime playedAt = LocalDateTime.now();

    @Column(name = "duration_secs")
    private Integer durationSecs;
}