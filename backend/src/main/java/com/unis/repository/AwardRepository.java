package com.unis.repository;

import com.unis.entity.Award;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface AwardRepository extends JpaRepository<Award, UUID> {

    // =========================================================================
    // EXISTING METHODS (updated for clarity)
    // =========================================================================

    /**
     * Find top awards by period for leaderboards
     */
    @Query(value = """
        SELECT * FROM awards a
        WHERE (:jurisdictionId IS NULL OR a.jurisdiction_id = :jurisdictionId)
          AND (:intervalId IS NULL OR a.interval_id = :intervalId)
          AND a.award_date BETWEEN :startDate AND :endDate
        ORDER BY a.votes_count DESC, a.engagement_score DESC
        LIMIT 50
        """, nativeQuery = true)
    List<Award> findTopByPeriod(
        @Param("jurisdictionId") UUID jurisdictionId,
        @Param("intervalId") UUID intervalId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    /**
     * Find awards with all filters (for Milestones page)
     */
    @Query(value = """
        SELECT * FROM awards a
        WHERE (:targetType IS NULL OR a.target_type = :targetType)
          AND (:jurisdictionId IS NULL OR a.jurisdiction_id = :jurisdictionId)
          AND (:genreId IS NULL OR a.genre_id = :genreId)
          AND (:intervalId IS NULL OR a.interval_id = :intervalId)
          AND a.award_date BETWEEN :startDate AND :endDate
        ORDER BY a.award_date DESC, a.votes_count DESC
        """, nativeQuery = true)
    List<Award> findByFilters(
        @Param("targetType") String targetType,
        @Param("jurisdictionId") UUID jurisdictionId,
        @Param("genreId") UUID genreId,
        @Param("intervalId") UUID intervalId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    // =========================================================================
    // DUPLICATE CHECK METHODS
    // =========================================================================

    /**
     * Check if an award exists for a specific target on a specific date
     * Used to prevent duplicate awards for the same target
     */
    @Query(value = """
        SELECT COUNT(*) FROM awards a 
        WHERE a.target_type = :targetType 
          AND a.target_id = :targetId 
          AND a.jurisdiction_id = :jurisdictionId 
          AND a.interval_id = :intervalId 
          AND a.award_date = :awardDate
        """, nativeQuery = true)
    Long existsByTargetTypeAndTargetIdAndJurisdictionIdAndIntervalIdAndAwardDate(
        @Param("targetType") String targetType,
        @Param("targetId") UUID targetId,
        @Param("jurisdictionId") UUID jurisdictionId,
        @Param("intervalId") UUID intervalId,
        @Param("awardDate") LocalDate awardDate
    );

    /**
     * Check if ANY award exists for a category (regardless of who won)
     * Used to prevent computing awards twice for the same category/date
     * 
     * This is the key check: ONE award per targetType + jurisdiction + genre + interval + date
     */
    @Query(value = """
        SELECT CASE WHEN COUNT(*) > 0 THEN true ELSE false END
        FROM awards a 
        WHERE a.target_type = :targetType 
          AND a.jurisdiction_id = :jurisdictionId 
          AND a.genre_id = :genreId
          AND a.interval_id = :intervalId 
          AND a.award_date = :awardDate
        """, nativeQuery = true)
    boolean existsAwardForCategory(
        @Param("targetType") String targetType,
        @Param("jurisdictionId") UUID jurisdictionId,
        @Param("genreId") UUID genreId,
        @Param("intervalId") UUID intervalId,
        @Param("awardDate") LocalDate awardDate
    );

    // =========================================================================
    // ENGAGEMENT TRACKING
    // =========================================================================

    /**
     * Increment engagement score for an award (called when votes come in)
     */
    @Modifying
    @Query(value = """
        UPDATE awards 
        SET engagement_score = engagement_score + 1 
        WHERE target_type = :targetType 
          AND target_id = :targetId 
          AND jurisdiction_id = :jurisdictionId 
          AND interval_id = :intervalId
        """, nativeQuery = true)
    void incrementAwardEngagement(
        @Param("targetType") String targetType,
        @Param("targetId") UUID targetId,
        @Param("jurisdictionId") UUID jurisdictionId,
        @Param("intervalId") UUID intervalId
    );

    // =========================================================================
    // QUERIES FOR SPECIFIC USE CASES
    // =========================================================================

    /**
     * Find all awards won by a specific target (artist or song)
     */
    @Query("SELECT a FROM Award a WHERE a.targetType = :targetType AND a.targetId = :targetId ORDER BY a.awardDate DESC")
    List<Award> findByTargetTypeAndTargetId(
        @Param("targetType") String targetType,
        @Param("targetId") UUID targetId
    );

    /**
     * Find award for a specific date (single winner per category)
     */
    @Query(value = """
        SELECT * FROM awards a
        WHERE a.target_type = :targetType
          AND a.jurisdiction_id = :jurisdictionId
          AND a.genre_id = :genreId
          AND a.interval_id = :intervalId
          AND a.award_date = :awardDate
        LIMIT 1
        """, nativeQuery = true)
    Award findWinnerForDate(
        @Param("targetType") String targetType,
        @Param("jurisdictionId") UUID jurisdictionId,
        @Param("genreId") UUID genreId,
        @Param("intervalId") UUID intervalId,
        @Param("awardDate") LocalDate awardDate
    );

    /**
     * Get all distinct dates that have awards (for historical view)
     */
    @Query("SELECT DISTINCT a.awardDate FROM Award a WHERE a.jurisdiction.jurisdictionId = :jurisdictionId ORDER BY a.awardDate DESC")
    List<LocalDate> findDistinctAwardDates(@Param("jurisdictionId") UUID jurisdictionId);

    /**
     * Count awards won by a target (for profile stats)
     */
    @Query("SELECT COUNT(a) FROM Award a WHERE a.targetType = :targetType AND a.targetId = :targetId")
    Long countAwardsByTarget(
        @Param("targetType") String targetType,
        @Param("targetId") UUID targetId
    );

    /**
     * Find awards determined by tiebreaker (for analytics)
     */
    @Query("SELECT a FROM Award a WHERE a.tiedCandidatesCount > 0 ORDER BY a.awardDate DESC")
    List<Award> findTiebrokenAwards();

    // =========================================================================
    // CLEANUP METHODS
    // =========================================================================

    /**
     * Delete all awards for recomputation (use with caution!)
     */
    @Modifying
    @Query("DELETE FROM Award a WHERE a.awardDate = :awardDate")
    void deleteByAwardDate(@Param("awardDate") LocalDate awardDate);

    /**
     * Delete awards for a specific jurisdiction (for testing)
     */
    @Modifying
    @Query("DELETE FROM Award a WHERE a.jurisdiction.jurisdictionId = :jurisdictionId")
    void deleteByJurisdictionId(@Param("jurisdictionId") UUID jurisdictionId);

    @Query("SELECT a FROM Award a WHERE a.targetId = :targetId AND a.targetType = 'artist' ORDER BY a.awardDate DESC")
    List<Award> findByTargetIdOrderByAwardDateDesc(@Param("targetId") UUID targetId, Pageable pageable);

    @Query("SELECT COUNT(a) FROM Award a WHERE a.awardDate = :date")
    int countByAwardDate(@Param("date") LocalDate date);

    @Query(value = """
    SELECT a.* FROM awards a
    JOIN songs s ON a.target_id = s.song_id
    WHERE a.target_type = 'song'
      AND s.artist_id = :artistId
    ORDER BY a.award_date DESC
    """, nativeQuery = true)
    List<Award> findSongAwardsByArtistId(
        @Param("artistId") UUID artistId,
        Pageable pageable
    );
}