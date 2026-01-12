package com.unis.repository;

import com.unis.entity.Vote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface VoteRepository extends JpaRepository<Vote, UUID> {
    // Count by target (song/artist)
    @Query(value = "SELECT COUNT(*) FROM votes v WHERE v.target_type = :targetType AND v.target_id = :targetId", nativeQuery = true)
    Long countByTarget(@Param("targetType") String targetType, @Param("targetId") UUID targetId);

    // Count by user
    @Query(value = "SELECT COUNT(*) FROM votes v WHERE v.user_id = :userId", nativeQuery = true)
    Long countByUserId(@Param("userId") UUID userId);

    // Find by jurisdiction/genre/interval (optional params—recursive for hierarchy)
    @Query(value = "WITH RECURSIVE jurisdiction_tree AS ( " +
                   "  SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId " +
                   "  UNION ALL " +
                   "  SELECT j.jurisdiction_id FROM jurisdictions j JOIN jurisdiction_tree jt ON j.parent_jurisdiction_id = jt.jurisdiction_id " +
                   ") " +
                   "SELECT v.* FROM votes v JOIN jurisdiction_tree jt ON v.jurisdiction_id = jt.jurisdiction_id " +
                   "WHERE (:genreId IS NULL OR v.genre_id = :genreId) " +
                   "AND (:intervalId IS NULL OR v.interval_id = :intervalId) " +
                   "ORDER BY v.vote_date DESC", nativeQuery = true)
    List<Vote> findByJurisdictionGenreInterval(@Param("jurisdictionId") UUID jurisdictionId, @Param("genreId") UUID genreId, @Param("intervalId") UUID intervalId);

    // Top vote counts for cron (per jurisdiction/interval)
    @Query(value = "SELECT v.target_id, COUNT(v) as voteCount FROM votes v WHERE v.jurisdiction_id = :jurisdictionId AND v.interval_id = :intervalId GROUP BY v.target_id ORDER BY voteCount DESC", nativeQuery = true)
    List<Object[]> findTopVoteCounts(@Param("jurisdictionId") UUID jurisdictionId, @Param("intervalId") UUID intervalId);

    // Top vote counts for specific date (for past cron)
    @Query(value = "SELECT v.target_id, COUNT(v) as voteCount FROM votes v WHERE v.jurisdiction_id = :jurisdictionId AND v.interval_id = :intervalId AND v.vote_date = :voteDate GROUP BY v.target_id ORDER BY voteCount DESC", nativeQuery = true)
    List<Object[]> findTopVoteCountsForDate(@Param("jurisdictionId") UUID jurisdictionId, @Param("intervalId") UUID intervalId, @Param("voteDate") LocalDate voteDate);

    // =========================================================================
    // FIXED: Duplicate check WITHOUT target_id
    // This enforces: One vote per user per category per jurisdiction per day
    // =========================================================================
    
    /**
     * Check if user has already voted in this category/jurisdiction/interval/date.
     * 
     * NOTE: This does NOT include target_id, meaning:
     * - User can only cast ONE artist vote per jurisdiction per day
     * - User can only cast ONE song vote per jurisdiction per day
     * - User CANNOT vote for Artist A, then change to Artist B (first vote is final)
     * 
     * @return Count of existing votes (0 = can vote, >0 = already voted)
     */
    @Query(value = """
        SELECT COUNT(*) FROM votes v 
        WHERE v.user_id = :userId 
          AND v.target_type = :targetType 
          AND v.genre_id = :genreId 
          AND v.jurisdiction_id = :jurisdictionId 
          AND v.interval_id = :intervalId 
          AND v.vote_date = :voteDate
        """, nativeQuery = true)
    Long existsByUserAndCategoryAndJurisdictionAndIntervalAndDate(
        @Param("userId") UUID userId, 
        @Param("targetType") String targetType, 
        @Param("genreId") UUID genreId,
        @Param("jurisdictionId") UUID jurisdictionId, 
        @Param("intervalId") UUID intervalId, 
        @Param("voteDate") LocalDate voteDate
    );

    // =========================================================================
    // DEPRECATED: Old method that included target_id (keeping for reference)
    // This allowed voting for multiple different targets per day - WRONG behavior
    // =========================================================================
    
    /**
     * @deprecated Use existsByUserAndCategoryAndJurisdictionAndIntervalAndDate instead
     */
    @Deprecated
    @Query(value = "SELECT COUNT(*) FROM votes v WHERE v.user_id = :userId AND v.target_type = :targetType AND v.target_id = :targetId AND v.genre_id = :genreId AND v.jurisdiction_id = :jurisdictionId AND v.interval_id = :intervalId AND v.vote_date = :voteDate", nativeQuery = true)
    Long existsByUserUserIdAndTargetTypeAndTargetIdAndGenreGenreIdAndJurisdictionJurisdictionIdAndIntervalIntervalIdAndVoteDate(
        @Param("userId") UUID userId, 
        @Param("targetType") String targetType, 
        @Param("targetId") UUID targetId, 
        @Param("genreId") UUID genreId, 
        @Param("jurisdictionId") UUID jurisdictionId, 
        @Param("intervalId") UUID intervalId, 
        @Param("voteDate") LocalDate voteDate
    );

    @Query(value = "SELECT v.target_id, COUNT(v) as voteCount FROM votes v WHERE v.jurisdiction_id = :jurisdictionId AND v.interval_id = :intervalId AND v.target_type = 'artist' AND v.vote_date = :voteDate GROUP BY v.target_id ORDER BY voteCount DESC", nativeQuery = true)
    List<Object[]> findTopArtistVoteCountsForDate(@Param("jurisdictionId") UUID jurisdictionId, @Param("intervalId") UUID intervalId, @Param("voteDate") LocalDate voteDate);

    // Top vote counts for range (for multi-interval cron)
    @Query(value = "SELECT v.target_id, COUNT(v) as voteCount FROM votes v WHERE v.jurisdiction_id = :jurisdictionId AND v.interval_id = :intervalId AND v.target_type = 'song' AND v.vote_date BETWEEN :startDate AND :endDate GROUP BY v.target_id ORDER BY voteCount DESC", nativeQuery = true)
    List<Object[]> findTopVoteCountsForRange(@Param("jurisdictionId") UUID jurisdictionId, @Param("intervalId") UUID intervalId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query(value = "SELECT v.target_id, COUNT(v) as voteCount FROM votes v WHERE v.jurisdiction_id = :jurisdictionId AND v.interval_id = :intervalId AND v.target_type = 'artist' AND v.vote_date BETWEEN :startDate AND :endDate GROUP BY v.target_id ORDER BY voteCount DESC", nativeQuery = true)
    List<Object[]> findTopArtistVoteCountsForRange(@Param("jurisdictionId") UUID jurisdictionId, @Param("intervalId") UUID intervalId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // =========================================================================
    // DELETE METHODS - REMOVED for soft delete approach
    // 
    // With soft delete, we DON'T delete votes when a user is deleted.
    // This preserves historical data for the Milestones page.
    // 
    // The database trigger (tr_votes_immutable) would block these anyway.
    // =========================================================================

    // REMOVED: deleteByUserUserId - votes cast BY user are preserved
    // REMOVED: deleteByTargetArtistId - votes cast ON artist are preserved

    // Find votes by user for vote history
    @Query("SELECT v FROM Vote v WHERE v.user.userId = :userId ORDER BY v.voteDate DESC")
    List<Vote> findByUserUserIdOrderByVoteDateDesc(@Param("userId") UUID userId);
}