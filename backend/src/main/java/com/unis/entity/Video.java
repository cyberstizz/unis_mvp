package com.unis.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "videos")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Video {
    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID videoId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artist_id", nullable = false)
    private User artist;

    @Column(nullable = false)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "genre_id")
    private Genre genre;

    // In JSON, the nested jurisdiction is just this jurisdiction: no map
    // polygon and no parent chain. Serializing parentJurisdiction loaded and
    // sent every ancestor (Harlem -> Manhattan -> ... -> Unis) inside every
    // song and user, about 1.5 KB each time, plus a query per level. The
    // jurisdiction endpoints still return polygons and parents for the map.
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"parentJurisdiction", "polygon"})
    @ManyToOne(fetch = FetchType.LAZY)  
    @JoinColumn(name = "jurisdiction_id")
    private Jurisdiction jurisdiction;

    @Column(name = "video_url")
    private String videoUrl;

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

    // ── Transient stats (decorated by MediaService, mirrors Song) ──
    @Transient
    private Long playCount;

    @Transient
    private Long playsToday;

    @Transient
    private Integer likes;

}