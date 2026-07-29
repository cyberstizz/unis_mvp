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
 * Populated in VoteService.getLeaderboard() in two branches:
 *   artist rows -> rank, targetId, name, votes, artwork
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
     * Owning artist for song rows, so a row can deep-link to the artist page.
     *
     * NOT POPULATED YET. VoteService's song query selects six columns
     * (song_id, title, score, artwork_url, artist, file_url) and none of them
     * is artist_id, so this always serializes as null today. To wire it: add
     * `s.artist_id` to that SELECT and `.artistId((UUID) row[6])` to the
     * builder alongside .fileUrl(...).
     */
    private UUID artistId;
}