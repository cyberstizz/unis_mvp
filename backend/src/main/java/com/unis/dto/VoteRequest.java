package com.unis.dto;

import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoteRequest {
    private UUID userId;
    private String targetType; 
    private UUID targetId;
    private UUID genreId;
    private UUID jurisdictionId;
    private UUID intervalId;
    private LocalDate voteDate;
}