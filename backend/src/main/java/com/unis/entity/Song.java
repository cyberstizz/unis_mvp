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

    @Column(name = "download_policy", nullable = false)
    @Builder.Default
    private String downloadPolicy = "free";  // "free", "paid", "unavailable"
 
    @Column(name = "download_price")
    private Integer downloadPrice; 

    // ── Discovery layer (subgenres, moods, language) ─────────────────────
    // For finding music only: browse shelves, the song page, the recommended
    // carousel. Voting, awards and charts still key off `genre` alone.
    //
    // primarySubgenre is always one of the song's own genre's subgenres
    // (MediaService enforces it). secondarySubgenre may come from any genre,
    // for crossover songs. If nothing fits, the artist types subgenreWriteIn
    // instead of a primary, and an admin maps it at the quarterly review.
    @ManyToOne
    @JoinColumn(name = "primary_subgenre_id")
    private Subgenre primarySubgenre;

    @ManyToOne
    @JoinColumn(name = "secondary_subgenre_id")
    private Subgenre secondarySubgenre;

    @ManyToOne
    @JoinColumn(name = "primary_mood_id")
    private Mood primaryMood;

    @ManyToOne
    @JoinColumn(name = "secondary_mood_id")
    private Mood secondaryMood;

    /** en, es, fr, other, or instrumental. Null on songs uploaded before this existed. */
    @Column(name = "language", length = 16)
    private String language;

    @Column(name = "subgenre_write_in", length = 60)
    private String subgenreWriteIn;

    @Transient
    private Long playCount;

     // NEW: Transient field for likes count (calculated on the fly)
    @Transient
    private Integer likes;

    // ── Removed songs ────────────────────────────────────────────────────
    // A removed song (deleted_at set) stays in the database so award history
    // and past leaderboards keep its title and artist. It must never be
    // playable or shown again, so every API response gets null for its audio
    // and artwork. JPA reads and writes the fields directly (the @Id is on a
    // field), so these getters change only what callers and JSON see.
    // MediaService.removeSong and the purge job use storedFileUrl() /
    // storedArtworkUrl() to reach the real files.

    public String getFileUrl() {
        return deletedAt != null ? null : fileUrl;
    }

    public String getArtworkUrl() {
        return deletedAt != null ? null : artworkUrl;
    }

    /** True once the song has been removed. Sent to the app as "removed". */
    public boolean isRemoved() {
        return deletedAt != null;
    }

    /** The real audio location, even after removal. Not exposed in JSON. */
    public String storedFileUrl() {
        return fileUrl;
    }

    /** The real artwork location, even after removal. Not exposed in JSON. */
    public String storedArtworkUrl() {
        return artworkUrl;
    }
}