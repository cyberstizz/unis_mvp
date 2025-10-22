package com.unis.dto;

import lombok.*;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoUploadRequest {
    private String title;
    private UUID genreId;
    private UUID artistId;
    private String description;
    private Integer duration;
}