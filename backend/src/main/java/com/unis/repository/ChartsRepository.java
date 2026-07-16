package com.unis.repository;

import com.unis.entity.SongPlay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Dedicated read-model repository for the Feed "Charts" lens.
 *
 * Ranks songs by play count within a time window. Jurisdiction scoping
 * goes through the song's jurisdiction using the same recursive
 * jurisdiction_tree pattern as VoteRepository, so querying an aggregate
 * jurisdiction (e.g. Harlem) includes plays of songs in its children
 * (Uptown / Downtown Harlem).
 */
@Repository
public interface ChartsRepository extends JpaRepository<SongPlay, UUID> {

    /**
     * Play counts per song for a jurisdiction subtree within a time range,
     * highest first. Deleted songs are excluded at the query level.
     * Returns rows of [song_id (UUID), playCount (Long)].
     */
    @Query(value =
        "WITH RECURSIVE jurisdiction_tree AS ( " +
        "  SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId " +
        "  UNION ALL " +
        "  SELECT j.jurisdiction_id FROM jurisdictions j " +
        "  JOIN jurisdiction_tree jt ON j.parent_jurisdiction_id = jt.jurisdiction_id " +
        ") " +
        "SELECT sp.song_id, COUNT(*) AS play_count " +
        "FROM song_plays sp " +
        "JOIN songs s ON s.song_id = sp.song_id " +
        "JOIN jurisdiction_tree jt ON s.jurisdiction_id = jt.jurisdiction_id " +
        "WHERE sp.played_at >= :startTime AND sp.played_at < :endTime " +
        "AND s.deleted_at IS NULL " +
        "GROUP BY sp.song_id " +
        "ORDER BY play_count DESC, sp.song_id",
        nativeQuery = true)
    List<Object[]> findSongPlayCountsForRange(
            @Param("jurisdictionId") UUID jurisdictionId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * Total plays in a jurisdiction subtree within a time range —
     * powers "1,204 plays this week".
     */
    @Query(value =
        "WITH RECURSIVE jurisdiction_tree AS ( " +
        "  SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId " +
        "  UNION ALL " +
        "  SELECT j.jurisdiction_id FROM jurisdictions j " +
        "  JOIN jurisdiction_tree jt ON j.parent_jurisdiction_id = jt.jurisdiction_id " +
        ") " +
        "SELECT COUNT(*) FROM song_plays sp " +
        "JOIN songs s ON s.song_id = sp.song_id " +
        "JOIN jurisdiction_tree jt ON s.jurisdiction_id = jt.jurisdiction_id " +
        "WHERE sp.played_at >= :startTime AND sp.played_at < :endTime " +
        "AND s.deleted_at IS NULL",
        nativeQuery = true)
    Long countPlaysForRange(
            @Param("jurisdictionId") UUID jurisdictionId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);
}