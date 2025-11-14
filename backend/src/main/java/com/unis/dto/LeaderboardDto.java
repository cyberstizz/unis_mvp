package com.unis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}