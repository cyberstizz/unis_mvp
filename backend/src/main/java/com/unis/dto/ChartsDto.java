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
 * Monthly "top voted" chart for a jurisdiction, with rank movement
 * relative to the previous calendar month.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChartsDto {

    /** e.g. "2026-07" */
    private String month;

    /** Total votes cast in this jurisdiction (including child jurisdictions) this month */
    private long totalVotesThisMonth;

    private List<ChartEntry> entries;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChartEntry {

        private int rank;

        /**
         * Positions moved since last month.
         * Positive = moved up, negative = moved down, 0 = held.
         * null = wasn't on last month's chart (render as NEW).
         */
        private Integer movement;

        private long votes;

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