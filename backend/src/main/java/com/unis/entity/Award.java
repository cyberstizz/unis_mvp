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
    // NEW FIELDS: Tiebreaker audit trail (added in Phase 1 schema)
    // =========================================================================

    /**
     * How the winner was determined:
     * - "VOTES" = Won by having the most votes (no tie)
     * - "SCORE" = Tied on votes, won by highest score
     * - "SENIORITY" = Tied on votes AND score, won by oldest account/song
     * - "FALLBACK" = No votes cast, showing top by score
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
     * 0 = No tie (clear winner by votes)
     * 2+ = That many candidates were tied
     */
    @Column(name = "tied_candidates_count")
    @Builder.Default
    private Integer tiedCandidatesCount = 0;

    /**
     * JSON details of the tiebreaker process (optional, for debugging/audit).
     * Example: {"tied_ids": ["uuid1", "uuid2"], "votes": 5, "scores": [100, 100]}
     */
    @Column(name = "tiebreaker_details", columnDefinition = "TEXT")
    private String tiebreakerDetails;

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
     * Check if this award was determined by tiebreaker (not clear vote winner)
     */
    public boolean wasTiebroken() {
        return tiedCandidatesCount != null && tiedCandidatesCount > 0;
    }

    /**
     * Get a human-readable description of how the winner was determined
     */
    public String getDeterminationDescription() {
        if (determinationMethod == null) {
            return "Winner by votes";
        }
        
        switch (determinationMethod) {
            case "VOTES":
                return "Winner by votes";
            case "SCORE":
                return "Tiebreaker: highest score (" + tiedCandidatesCount + " tied on votes)";
            case "SENIORITY":
                return "Tiebreaker: oldest account (" + tiedCandidatesCount + " tied on votes & score)";
            case "FALLBACK":
                return "No votes cast - top by score";
            default:
                return determinationMethod;
        }
    }
}