package com.unis.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * One row of the live leaderboard.
 *
 * This is a ROW type, not a wrapper — VoteController returns
 * ResponseEntity<List<LeaderboardDto>> and the frontend maps over a bare
 * array. Do not add an "entries" field here.
 *
 * Field names are load-bearing. leaderboardsPage.jsx reads targetId, rank,
 * name, votes, artwork, artist and fileUrl straight off each item, so renaming
 * any of them silently breaks the Milestones page. Note it is `name`, not
 * `title` — LeaderboardEntryDto uses `title`, and the two are not
 * interchangeable.
 *
 * Populated in VoteService.getLeaderboard() from the race's live board
 * (RaceStandingsService):
 *   artist rows -> rank, targetId, name, votes, artwork, artistId
 *   song rows   -> the same, plus artist and fileUrl
 */
@Data
@Builder
public class LeaderboardDto {

    private int rank;

    /** user_id for artist rows, song_id for song rows. */
    private UUID targetId;

    /** Username for artist rows, song title for song rows. */
    private String name;

    /**
     * The number the race is decided on: total points (weighted vote points +
     * plays + 5 x likes — RankingService). Kept under this name because the
     * leaderboards page reads it; the breakdown is in the fields below.
     */
    private long votes;

    private String artwork;

    /** Performing artist. Null for artist rows. */
    private String artist;

    /**
     * Audio source for song rows, so a leaderboard row can route through
     * PlayerContext.requestPlay() and the PlayChoiceModal like every other
     * play surface. Null for artist rows.
     */
    private String fileUrl;

    /**
     * The artist: the performing artist on song rows, the artist themself on
     * artist rows, so a row can deep-link to the artist page.
     */
    private UUID artistId;

    /** Same as votes: total points. */
    private int totalPoints;

    /** Votes received in this period (raw count, before interval weighting). */
    private int voteCount;

    /** Plays in this period, on the artist's calendar. */
    private int plays;

    /** Likes in this period, on the artist's calendar. */
    private int likes;
}