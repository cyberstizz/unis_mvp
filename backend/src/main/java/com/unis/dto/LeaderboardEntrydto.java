package com.unis.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class LeaderboardEntryDto {
    private int rank;
    private UUID targetId;
    private String targetType;
    private String title;
    private String artist;
    private String artwork;
    private long votes;
    private int weightedPoints;
    private int playsCount;
    private int likesCount;
    private boolean isWinner;
    private String determinationMethod;
    private Integer tiedCandidatesCount;
}