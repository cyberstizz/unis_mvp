package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A release: a group of an artist's songs with its own title, type and cover
 * (album, EP, mixtape or single). Songs point at their release through
 * songs.release_id and keep their own plays, likes, votes and races.
 * See sql/V2026_11e__releases_and_artist_teams.sql.
 */
@Entity
@Table(name = "releases")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Release {

    public enum Type { ALBUM, EP, MIXTAPE, SINGLE }

    @Id
    @GeneratedValue
    @Column(name = "release_id", columnDefinition = "UUID")
    private UUID releaseId;

    @Column(name = "artist_id", nullable = false)
    private UUID artistId;

    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_type", nullable = false, length = 10)
    private Type releaseType;

    @Column(name = "cover_url")
    private String coverUrl;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "released_at", nullable = false)
    private LocalDateTime releasedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
