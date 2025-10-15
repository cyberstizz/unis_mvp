package com.unis.repository;

import com.unis.entity.Award;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface AwardRepository extends JpaRepository<Award, UUID> {
    @Query("SELECT a FROM Award a WHERE a.jurisdiction.jurisdictionId = :jurisdictionId AND a.interval.intervalId = :intervalId AND a.awardDate BETWEEN :start AND :end ORDER BY a.votesCount DESC")
    List<Award> findTopByPeriod(@Param("jurisdictionId") UUID jurisdictionId, @Param("intervalId") UUID intervalId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    // For cron: Top candidates by votes (native for GROUP BY/alias)
    @Query(value = "SELECT v.target_id, COUNT(v) as voteCount FROM votes v WHERE v.jurisdiction_id = :jurisdictionId AND v.interval_id = :intervalId GROUP BY v.target_id ORDER BY voteCount DESC", nativeQuery = true)
    List<Object[]> findTopVoteCounts(@Param("jurisdictionId") UUID jurisdictionId, @Param("intervalId") UUID intervalId);
}