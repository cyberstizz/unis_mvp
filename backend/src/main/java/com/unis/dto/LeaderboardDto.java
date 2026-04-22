package com.unis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeaderboardDto {
    private int rank;
    private String name;
    private Long votes;
    private String artwork;
    private String artist;
    private UUID targetId;
    private String fileUrl;
}