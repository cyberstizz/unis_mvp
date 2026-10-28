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

    // Download settings chosen in the upload wizard. Before these existed the
    // keys were silently ignored and every upload was saved as a free download.
    // downloadPrice is in cents. Missing = free.
    private String downloadPolicy;   // "free" | "paid" | "unavailable"
    private Integer downloadPrice;

    // The uploader ticked the rights box (Terms 3.6): they control this song,
    // no publisher or publishing administrator handles it, no writer is with
    // SESAC or GMR, and it isn't a cover or built on uncleared samples. Every
    // upload must send true; MediaService refuses anything else.
    private Boolean rightsConfirmed;
}