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
}