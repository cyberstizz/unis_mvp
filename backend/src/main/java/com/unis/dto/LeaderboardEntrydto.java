package com.unis.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * One ranked entry in a period leaderboard (Milestones page).
 * Hydrated from CandidateResult rows in AwardService.
 *
 * Note: `determinationMethod` and `tiedCandidatesCount` are populated only
 * for the winning entry (rank 1, isWinner=true). All other rows leave them null.
 */
@Data
@Builder
public class LeaderboardEntrydto {
    private int rank;
    private UUID targetId;
    private String targetType;       // "song" or "artist"
    private String title;
    private String artist;
    private String artwork;          // raw URL/path; frontend prepends API_BASE_URL if relative

    private long votes;              // raw vote count during the period
    private int weightedPoints;      // sum of vote weights (the determinant)
    private int playsCount;
    private int likesCount;

    private boolean isWinner;        // true for the entry that was awarded

    // Populated only on the winning entry
    private String determinationMethod;
    private Integer tiedCandidatesCount;
}