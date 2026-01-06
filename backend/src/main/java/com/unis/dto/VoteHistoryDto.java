package com.unis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoteHistoryDto {
    private UUID voteId;
    private String targetType;      // "song" or "artist"
    private UUID targetId;
    private String nomineeName;     // Song title or Artist username
    private String nomineeImage;    // Artwork URL or Photo URL
    private LocalDate voteDate;
    private String interval;        // "day", "week", "month", "quarter", "midterm", "year"
}
