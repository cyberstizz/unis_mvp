package com.unis.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * One row of the Milestones tally.
 *
 * Distinct from LeaderboardDto, which serves the live /vote/leaderboards
 * endpoint. This one is used by AwardService and PeriodLeaderboardDto and
 * carries award-determination metadata the live leaderboard has no use for.
 * Note this type uses `title` where LeaderboardDto uses `name`.
 *
 * fileUrl and artistId were added so a Milestones row can route through
 * PlayerContext.requestPlay() and the PlayChoiceModal like every other play
 * surface in the app. Without fileUrl the Milestones page could rank songs but
 * never play them, which failed the "playChoiceModal on every play button"
 * standard and meant a play from this page earned the artist no points.
 *
 * Both fields are null for artist-type rows.
 */
@Data
@Builder
public class LeaderboardEntryDto {
    private int rank;
    private UUID targetId;
    private String targetType;
    private String title;
    private String artist;
    private String artwork;

    /** Audio source for song rows. Null for artist rows. */
    private String fileUrl;

    /** Owning artist for song rows, so the row can deep-link. Null for artist rows. */
    private UUID artistId;

    private long votes;
    private int weightedPoints;
    private int playsCount;
    private int likesCount;
    private boolean isWinner;
    private String determinationMethod;
    private Integer tiedCandidatesCount;
}