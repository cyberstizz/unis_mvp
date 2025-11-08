package com.unis.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "songs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class Song {
    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID songId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artist_id", nullable = false)
    private User artist;

    @Column(nullable = false)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "genre_id")
    private Genre genre;

    @ManyToOne(fetch = FetchType.LAZY)  
    @JoinColumn(name = "jurisdiction_id")
    private Jurisdiction jurisdiction;

    @Column(name = "file_url")
    private String fileUrl;

    @Column(name = "artwork_url")
    private String artworkUrl;

    @Builder.Default
    @Column
    private Integer score = 0;

    @Builder.Default
    @Column(name = "level")
    private String level = "silver";

    @Builder.Default
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column
    private String description;

    @Column
    private Integer duration;
}