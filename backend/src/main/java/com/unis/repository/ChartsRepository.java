package com.unis.repository;

import com.unis.entity.Vote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Dedicated read-model repository for the Feed "Charts" lens.
 *
 * Lives alongside VoteRepository (same Vote entity) so no existing
 * repository files need to change. Uses the same recursive
 * jurisdiction_tree pattern as VoteRepository so that querying an
 * aggregate jurisdiction (e.g. Harlem) includes votes cast in its
 * children (Uptown / Downtown Harlem).
 */
@Repository
public interface ChartsRepository extends JpaRepository<Vote, UUID> {

    /**
     * Vote counts per song for a jurisdiction subtree within a date range,
     * highest first. Returns rows of [target_id (UUID), voteCount (Long)].
     */
    @Query(value =
        "WITH RECURSIVE jurisdiction_tree AS ( " +
        "  SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId " +
        "  UNION ALL " +
        "  SELECT j.jurisdiction_id FROM jurisdictions j " +
        "  JOIN jurisdiction_tree jt ON j.parent_jurisdiction_id = jt.jurisdiction_id " +
        ") " +
        "SELECT v.target_id, COUNT(*) AS vote_count " +
        "FROM votes v " +
        "JOIN jurisdiction_tree jt ON v.jurisdiction_id = jt.jurisdiction_id " +
        "WHERE v.target_type = 'song' " +
        "AND v.vote_date BETWEEN :startDate AND :endDate " +
        "GROUP BY v.target_id " +
        "ORDER BY vote_count DESC, v.target_id",
        nativeQuery = true)
    List<Object[]> findSongVoteCountsForRange(
            @Param("jurisdictionId") UUID jurisdictionId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Total votes (songs + artists) cast in a jurisdiction subtree
     * within a date range — powers "412 votes cast this month".
     */
    @Query(value =
        "WITH RECURSIVE jurisdiction_tree AS ( " +
        "  SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId " +
        "  UNION ALL " +
        "  SELECT j.jurisdiction_id FROM jurisdictions j " +
        "  JOIN jurisdiction_tree jt ON j.parent_jurisdiction_id = jt.jurisdiction_id " +
        ") " +
        "SELECT COUNT(*) FROM votes v " +
        "JOIN jurisdiction_tree jt ON v.jurisdiction_id = jt.jurisdiction_id " +
        "WHERE v.vote_date BETWEEN :startDate AND :endDate",
        nativeQuery = true)
    Long countVotesForRange(
            @Param("jurisdictionId") UUID jurisdictionId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}