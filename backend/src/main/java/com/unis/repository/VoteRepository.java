package com.unis.repository;

import com.unis.entity.Vote;
import org.springframework.data.jpa.repository.JpaRepository;
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

    // Exists for unique check (returns Long count > 0)
    @Query(value = "SELECT COUNT(*) FROM votes v WHERE v.user_id = :userId AND v.target_type = :targetType AND v.target_id = :targetId AND v.genre_id = :genreId AND v.jurisdiction_id = :jurisdictionId AND v.interval_id = :intervalId AND v.vote_date = :voteDate", nativeQuery = true)
    Long existsByUserUserIdAndTargetTypeAndTargetIdAndGenreGenreIdAndJurisdictionJurisdictionIdAndIntervalIntervalIdAndVoteDate(@Param("userId") UUID userId, @Param("targetType") String targetType, @Param("targetId") UUID targetId, @Param("genreId") UUID genreId, @Param("jurisdictionId") UUID jurisdictionId, @Param("intervalId") UUID intervalId, @Param("voteDate") LocalDate voteDate);
}