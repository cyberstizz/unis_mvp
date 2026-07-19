package com.unis.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.UUID;

/**
 * Video upload metadata.
 *
 * ignoreUnknown = true is REQUIRED, not defensive polish: the upload wizard
 * shares one metadata object across both media types, so a video POST also
 * carries song-only keys (explicit, isrc, downloadPolicy, downloadPrice).
 * Without this annotation Jackson throws UnrecognizedPropertyException and
 * every video upload 500s with "JSON parse error" — which is exactly what
 * happened. SongUploadRequest has always had it, which is why songs worked
 * and videos never did.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class VideoUploadRequest {
    private String title;
    private UUID genreId;
    private UUID artistId;
    private String description;
    private UUID jurisdictionId;  
    private Integer duration;
}