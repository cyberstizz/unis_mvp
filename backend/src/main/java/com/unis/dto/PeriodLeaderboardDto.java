package com.unis.dto;

import com.unis.entity.Award;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PeriodLeaderboardDto {
    private Award winner;
    private List<LeaderboardEntryDto> leaderboard;
    private int totalVotes;

    /** OPEN | FINAL | PENDING | NO_AWARD — see AwardService.getPeriodLeaderboard. */
    private String status;

    /** When this period ends for the last person in the race (ISO-8601 instant, UTC). */
    private String closesAt;

    /** Timezones the period is still running in, e.g. "America/Los_Angeles". Empty once closed. */
    private List<String> stillOpenIn;
}