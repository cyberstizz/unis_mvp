package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "songs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Song {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(name = "song_id")
    private UUID songId;

    @Column(nullable = false)
    private String title;

    @ManyToOne
    @JoinColumn(name = "artist_id", nullable = false)
    private User artist;

    @ManyToOne
    @JoinColumn(name = "genre_id")
    private Genre genre;

    @ManyToOne
    @JoinColumn(name = "jurisdiction_id")
    private Jurisdiction jurisdiction;

    @Column(columnDefinition = "TEXT")
    private String description;

    private Integer duration; // in milliseconds

    @Column(name = "file_url")
    private String fileUrl;

    @Column(name = "artwork_url")
    private String artworkUrl;

    private Integer score;

    private String level; // silver, gold, platinum

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // NEW FIELDS
    @Column(name = "explicit")
    private Boolean explicit = false;

    @Column(name = "lyrics", columnDefinition = "TEXT")
    private String lyrics;

    @Column(name = "plays_today")
    private Integer playsToday = 0;

    @Column(name = "last_play_reset_date")
    private LocalDate lastPlayResetDate = LocalDate.now();

    @Column(length = 15)
    private String isrc;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "clean_version_id")
    private UUID cleanVersionId;

    @Transient
    private Long playCount;

     // NEW: Transient field for likes count (calculated on the fly)
    @Transient
    private Integer likes;

}