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
    // Check if vote exists (for unique constraint enforcement)
    boolean existsByUserUserIdAndTargetTypeAndTargetIdAndGenreGenreIdAndJurisdictionJurisdictionIdAndIntervalIntervalIdAndVoteDate(
        UUID userId, String targetType, UUID targetId, UUID genreId, UUID jurisdictionId, UUID intervalId, LocalDate voteDate);

    // Total votes for a target (e.g., for cards: totalVotes)
    @Query("SELECT COUNT(v) FROM Vote v WHERE v.targetType = :targetType AND v.targetId = :targetId")
    Long countByTarget(@Param("targetType") String targetType, @Param("targetId") UUID targetId);

    // Total votes cast by user (for score: +2 each)
    @Query("SELECT COUNT(v) FROM Vote v WHERE v.user.userId = :userId")
    Long countByUserId(@Param("userId") UUID userId);

    // For vote page results: Votes by jurisdiction/genre/interval (filtered)
    @Query("SELECT v FROM Vote v WHERE v.jurisdiction.jurisdictionId = :jurisdictionId AND v.genre.genreId = :genreId AND v.interval.intervalId = :intervalId")
    List<Vote> findByJurisdictionGenreInterval(@Param("jurisdictionId") UUID jurisdictionId, 
                                               @Param("genreId") UUID genreId, 
                                               @Param("intervalId") UUID intervalId);

    // For score batch: Votes received by target (artist/song +3 each)
    @Query(value = "SELECT v.target_id, COUNT(v.vote_id) * 3 as vote_points " +
                   "FROM votes v " +
                   "WHERE v.target_type = :targetType " +
                   "GROUP BY v.target_id", nativeQuery = true)
    List<Object[]> computeVotePointsByTarget(@Param("targetType") String targetType);  // [targetId, points]

    // For awards cron: Top targets by vote count (per jurisdiction/interval)
    @Query(value = "SELECT v.target_id, COUNT(v) as voteCount " +
                   "FROM votes v " +
                   "WHERE v.jurisdiction_id = :jurisdictionId AND v.interval_id = :intervalId AND v.vote_date = CURRENT_DATE " +
                   "GROUP BY v.target_id " +
                   "ORDER BY voteCount DESC " +
                   "LIMIT 20", nativeQuery = true)
    List<Object[]> findTopVoteCounts(@Param("jurisdictionId") UUID jurisdictionId, @Param("intervalId") UUID intervalId);

    // Modifying: Increment engagement for ongoing awards (if vote on active period)
    @Modifying
    @Query("UPDATE Award a SET a.votesCount = a.votesCount + 1, a.engagementScore = a.engagementScore + 1 " +
           "WHERE a.targetType = :targetType AND a.targetId = :targetId AND a.jurisdiction.jurisdictionId = :jurisdictionId AND a.interval.intervalId = :intervalId")
    void incrementAwardEngagement(@Param("targetType") String targetType, @Param("targetId") UUID targetId, 
                                  @Param("jurisdictionId") UUID jurisdictionId, @Param("intervalId") UUID intervalId);
}