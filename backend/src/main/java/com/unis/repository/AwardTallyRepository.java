package com.unis.repository;

import com.unis.dto.AwardTallyDto;
import com.unis.entity.Award;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Award tallies for the artist page medal rails.
 *
 * <p>Deliberately a separate repository from {@code AwardRepository} so this
 * feature drops in without touching existing files. Both queries are pure
 * aggregates over the {@code awards} table — one row already exists per win,
 * so no counter columns are needed anywhere.
 *
 * <p>Performance: add the composite index in
 * {@code V__add_awards_target_idx.sql}. The existing {@code idx_awards_lookup}
 * leads with {@code jurisdiction_id} and cannot serve a target-first lookup,
 * so without the new index these degrade to a sequential scan as the awards
 * table grows.
 */
@Repository
public interface AwardTallyRepository extends JpaRepository<Award, UUID> {

    /**
     * How many times this artist has won each interval's artist award.
     * Returns only intervals with at least one win — the rail renders nothing
     * for awards never earned.
     */
    @Query("""
        SELECT new com.unis.dto.AwardTallyDto('artist', LOWER(a.interval.name), COUNT(a))
        FROM Award a
        WHERE a.targetType = 'artist'
          AND a.targetId = :artistId
        GROUP BY a.interval.name
        """)
    List<AwardTallyDto> tallyArtistAwards(@Param("artistId") UUID artistId);

    /**
     * How many times any of this artist's songs has won each interval's song
     * award. Soft-deleted songs are excluded so a removed track doesn't keep
     * inflating the artist's prestige.
     */
    @Query("""
        SELECT new com.unis.dto.AwardTallyDto('song', LOWER(a.interval.name), COUNT(a))
        FROM Award a
        WHERE a.targetType = 'song'
          AND a.targetId IN (
              SELECT s.songId FROM Song s
              WHERE s.artist.userId = :artistId
                AND s.deletedAt IS NULL
          )
        GROUP BY a.interval.name
        """)
    List<AwardTallyDto> tallySongAwards(@Param("artistId") UUID artistId);
}