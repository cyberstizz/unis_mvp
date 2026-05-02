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
}