package com.unis.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * A discovery label under one of the three votable genres (rap, rock, pop).
 *
 * Subgenres are for FINDING music — browse shelves, the song page, the
 * recommended carousel. They are never voted on and never become award
 * races; that stays on {@link Genre}. Rows are seeded by
 * sql/V2026_10c__subgenres_moods_language.sql with fixed ids, so renaming a
 * subgenre (edit `name`) never re-tags a song.
 *
 * The parent genre is stored as a plain id rather than a relation so the JSON
 * stays flat ({subgenreId, genreId, slug, name, soundsLike}) everywhere a song
 * is serialized.
 */
@Entity
@Table(name = "subgenres")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Subgenre {

    @Id
    @Column(name = "subgenre_id", columnDefinition = "UUID")
    private UUID subgenreId;

    @Column(name = "genre_id", nullable = false, columnDefinition = "UUID")
    private UUID genreId;

    @Column(nullable = false, unique = true, length = 60)
    private String slug;

    @Column(nullable = false, length = 80)
    private String name;

    /** Shown under the option at upload: "Pop Smoke, Kay Flock". */
    @Column(name = "sounds_like", length = 160)
    private String soundsLike;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /** Inactive subgenres disappear from the upload list but stay on old songs. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;
}
