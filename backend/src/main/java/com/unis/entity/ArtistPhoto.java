package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "artist_photos")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArtistPhoto {

    @Id
    @GeneratedValue
    @Column(name = "photo_id", columnDefinition = "UUID")
    private UUID photoId;

    @Column(name = "artist_id", nullable = false, columnDefinition = "UUID")
    private UUID artistId;

    @Column(name = "photo_url", nullable = false)
    private String photoUrl;

    @Column(name = "position", nullable = false)
    @Builder.Default
    private Integer position = 0;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}