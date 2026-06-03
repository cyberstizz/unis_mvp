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
    @Builder.Default
    private LocalDateTime playedAt = LocalDateTime.now();

    @Column(name = "duration_secs")
    private Integer durationSecs;

    @Column(name = "completed")
    private Boolean completed;

    @Column(name = "percent_played")
    private java.math.BigDecimal percentPlayed;

    @Column(name = "source")
    private String source;

    @Column(name = "listener_jurisdiction_id")
    private UUID listenerJurisdictionId;   // raw UUID, not @ManyToOne — avoids a join on a write-heavy table

}