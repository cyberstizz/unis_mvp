package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "video_plays")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoPlay {
    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID playId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id")
    private Video video;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "played_at")
    private LocalDateTime playedAt = LocalDateTime.now();

    @Column(name = "duration_secs")
    private Integer durationSecs;
}