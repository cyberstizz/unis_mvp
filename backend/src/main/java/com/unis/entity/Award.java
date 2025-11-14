package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "awards", uniqueConstraints = {
  @UniqueConstraint(columnNames = {"target_type", "target_id", "jurisdiction_id", "interval_id", "award_date"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Award {
    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID awardId;

    @Column(name = "target_type", nullable = false)
    private String targetType;  // 'artist' or 'song' or 'video'

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "genre_id")
    private Genre genre;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "jurisdiction_id")
    private Jurisdiction jurisdiction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interval_id")
    private VotingInterval interval;

    @Column(name = "award_date", nullable = false)
    private LocalDate awardDate;

    @Column(name = "votes_count")
    private Integer votesCount = 0;

    @Column(name = "engagement_score")
    private Integer engagementScore = 0;

    @Column
    private Integer weight = 100;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "caption")
    private String caption;
}