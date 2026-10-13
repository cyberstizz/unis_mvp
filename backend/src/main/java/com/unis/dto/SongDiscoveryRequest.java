package com.unis.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.UUID;

/**
 * Body of PATCH /api/v1/media/song/{songId}/discovery — an artist tagging or
 * re-tagging one of their songs. Same fields and rules as an upload; see
 * MediaService.applyDiscovery.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class SongDiscoveryRequest {
    private UUID primarySubgenreId;
    private UUID secondarySubgenreId;
    private String subgenreWriteIn;
    private UUID primaryMoodId;
    private UUID secondaryMoodId;
    private String language;
}
