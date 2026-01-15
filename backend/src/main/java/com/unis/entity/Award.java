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
    @Builder.Default
    private Integer votesCount = 0;

    @Column(name = "engagement_score")
    @Builder.Default
    private Integer engagementScore = 0;

    @Column
    @Builder.Default
    private Integer weight = 100;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "caption")
    private String caption;

    // =========================================================================
    // TIEBREAKER AUDIT FIELDS
    // =========================================================================

    /**
     * How the winner was determined:
     * - "WEIGHTED_VOTES" = Won by having the most weighted vote points (no tie)
     * - "PLAYS" = Tied on weighted votes, won by most plays
     * - "LIKES" = Tied on weighted votes AND plays, won by most likes
     * - "SCORE" = Tied on weighted votes, plays, AND likes, won by highest score
     * - "SENIORITY" = Tied on everything, won by oldest account/song
     * - "FALLBACK" = No votes cast, showing top by plays/likes/score/seniority
     */
    @Column(name = "determination_method")
    private String determinationMethod;

    /**
     * The created_at timestamp of the winner.
     * Stored for audit purposes when seniority is the tiebreaker.
     */
    @Column(name = "winner_seniority")
    private LocalDateTime winnerSeniority;

    /**
     * Number of candidates that were tied before tiebreaker was applied.
     * 0 = No tie (clear winner by weighted votes)
     * 2+ = That many candidates were tied
     */
    @Column(name = "tied_candidates_count")
    @Builder.Default
    private Integer tiedCandidatesCount = 0;

    /**
     * JSON details of the tiebreaker process (optional, for debugging/audit).
     * Example: {"tied_ids": ["uuid1", "uuid2"], "weighted_points": 270, "plays": [15, 15]}
     */
    @Column(name = "tiebreaker_details", columnDefinition = "TEXT")
    private String tiebreakerDetails;

    // =========================================================================
    // NEW AUDIT FIELDS FOR WEIGHTED SCORING SYSTEM
    // =========================================================================

    /**
     * Total weighted points from votes.
     * Calculated as: SUM(vote_weight) where weights are:
     * Annual=250, Midterm=200, Quarterly=60, Monthly=25, Weekly=20, Daily=10
     */
    @Column(name = "weighted_points")
    @Builder.Default
    private Integer weightedPoints = 0;

    /**
     * Total song plays during the interval (used for tiebreaker #2).
     * For artists: sum of plays across all their songs.
     * For songs: plays for that specific song.
     */
    @Column(name = "plays_count")
    @Builder.Default
    private Integer playsCount = 0;

    /**
     * Total likes during the interval (used for tiebreaker #3).
     * For artists: sum of likes across all their songs.
     * For songs: likes for that specific song.
     */
    @Column(name = "likes_count")
    @Builder.Default
    private Integer likesCount = 0;

    // =========================================================================
    // TRANSIENT FIELDS: For API responses (not persisted)
    // =========================================================================

    @Transient
    private Song song;

    @Transient
    private User user;

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    /**
     * Check if this award was determined by tiebreaker (not clear weighted vote winner)
     */
    public boolean wasTiebroken() {
        return tiedCandidatesCount != null && tiedCandidatesCount > 0;
    }

    /**
     * Get a human-readable description of how the winner was determined
     */
    public String getDeterminationDescription() {
        if (determinationMethod == null) {
            return "Winner by weighted votes";
        }
        
        switch (determinationMethod) {
            case "WEIGHTED_VOTES":
                return "Winner by weighted votes (" + weightedPoints + " points)";
            case "PLAYS":
                return "Tiebreaker: most plays (" + tiedCandidatesCount + " tied on " + weightedPoints + " points)";
            case "LIKES":
                return "Tiebreaker: most likes (" + tiedCandidatesCount + " tied on points & plays)";
            case "SCORE":
                return "Tiebreaker: highest score (" + tiedCandidatesCount + " tied on points, plays & likes)";
            case "SENIORITY":
                return "Tiebreaker: oldest account (" + tiedCandidatesCount + " tied on all metrics)";
            case "FALLBACK":
                return "No votes cast - top performer by engagement";
            default:
                return determinationMethod;
        }
    }
}