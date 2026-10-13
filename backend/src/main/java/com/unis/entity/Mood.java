package com.unis.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * How a song feels, independent of its genre: Hype, Chill, Heartbreak, etc.
 * A song carries one or two. Used for discovery only, never for voting.
 * Seeded by sql/V2026_10c__subgenres_moods_language.sql with fixed ids.
 */
@Entity
@Table(name = "moods")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Mood {

    @Id
    @Column(name = "mood_id", columnDefinition = "UUID")
    private UUID moodId;

    @Column(nullable = false, unique = true, length = 60)
    private String slug;

    @Column(nullable = false, length = 60)
    private String name;

    /** Shown under the option at upload: "Turn up, gym, pre-game". */
    @Column(length = 120)
    private String description;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;
}
