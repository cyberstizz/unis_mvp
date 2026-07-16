package com.unis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Response shape for GET /api/v1/charts
 *
 * "Most played this week" chart for a jurisdiction, with rank movement
 * relative to the previous 7-day window. Play-based rather than
 * vote-based so the chart has real data even with a small user base.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChartsDto {

    /** Total plays in this jurisdiction (including child jurisdictions) over the last 7 days */
    private long totalPlaysThisWeek;

    private List<ChartEntry> entries;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChartEntry {

        private int rank;

        /**
         * Positions moved since the previous 7-day window.
         * Positive = moved up, negative = moved down, 0 = held.
         * null = wasn't on last week's chart (render as NEW).
         */
        private Integer movement;

        private long plays;

        private UUID songId;
        private String title;
        private String artworkUrl;
        private String fileUrl;
        private Integer duration;
        private Boolean explicit;

        private UUID artistId;
        private String artistName;
    }
}