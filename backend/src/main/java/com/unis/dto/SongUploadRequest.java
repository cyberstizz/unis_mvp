package com.unis.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)  
public class SongUploadRequest {
    private String title;
    private UUID genreId;
    private UUID artistId;
    private String description;
    private UUID jurisdictionId;  
    private Integer duration;  
    private Boolean explicit = false;
    private String lyrics;
    private String isrc;
    private Boolean isCleanVersion = false;

    // Discovery layer — see MediaService.applyDiscovery for the rules.
    private UUID primarySubgenreId;
    private UUID secondarySubgenreId;
    private String subgenreWriteIn;
    private UUID primaryMoodId;
    private UUID secondaryMoodId;
    private String language;
}