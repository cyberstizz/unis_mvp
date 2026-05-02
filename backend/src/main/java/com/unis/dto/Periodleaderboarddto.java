package com.unis.dto;

import com.unis.entity.Award;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response wrapper for GET /api/v1/awards/period-leaderboard
 *
 *   - winner:      the saved Award row (with determinationMethod, tiedCandidatesCount, etc.)
 *                  May be null if no Award has been computed yet AND auto-populate is disabled.
 *   - leaderboard: top N candidates for the period, ranked by the same cascade used to
 *                  determine the winner (weighted points → plays → likes → score → seniority).
 *                  Entry with isWinner=true matches the saved Award's targetId.
 *   - totalVotes:  sum of raw vote counts across all candidates for the period —
 *                  used by the frontend's "Decided by N votes" caption.
 */
@Data
@Builder
public class PeriodLeaderboardDto {
    private Award winner;
    private List<LeaderboardEntryDto> leaderboard;
    private int totalVotes;
}