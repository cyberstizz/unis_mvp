package com.unis.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Standings for one race, as a board should show them right now.
 *
 * state:
 *   LIVE              the current period has activity — these are its standings
 *   PREVIOUS_FINAL    nobody has scored in the current period yet, so the board
 *                     shows how the previous period closed, winner first
 *                     ("Final · Yesterday", "Last week's final")
 *   PREVIOUS_CLOSING  nobody has scored in the current period yet and the
 *                     previous period is still running somewhere in this race
 *                     (the Unis-wide race at 1 AM New York time, still open in
 *                     Pacific time). Its standings are live, not final;
 *                     closesAt and stillOpenIn say when and where.
 */
@Data
@Builder
public class RaceStandingsDto {

    private String type;              // "artist" | "song"
    private UUID jurisdictionId;
    private UUID genreId;
    private UUID intervalId;
    private String interval;          // "Daily", "Weekly", ...

    private String state;

    /** The period these standings are for. */
    private LocalDate periodStart;
    private LocalDate periodEnd;

    /** The period now running (equals the shown period when LIVE). */
    private LocalDate currentPeriodStart;
    private LocalDate currentPeriodEnd;

    /** When the shown period ends for the last person in the race (ISO-8601, UTC). Null once final. */
    private String closesAt;

    /** Timezones the shown period is still running in. Empty once final. */
    private List<String> stillOpenIn;

    /** The race jurisdiction's own clock, e.g. "America/New_York". */
    private String raceTimezone;

    private int limit;
    private int offset;

    private List<Entry> entries;

    @Data
    @Builder
    public static class Entry {
        private int rank;
        private UUID targetId;
        private String targetType;

        /** Artist name for artist rows, song title for song rows. */
        private String name;

        /** Performing artist (song rows), the artist themself (artist rows). */
        private String artistName;
        private UUID artistId;

        private String artworkUrl;
        private String fileUrl;     // song rows only

        private int totalPoints;
        private int votePoints;
        private int votes;
        private int engagementPoints;
        private int plays;
        private int likes;

        /** True only on the recorded award winner of a final period. */
        private boolean winner;
    }
}
