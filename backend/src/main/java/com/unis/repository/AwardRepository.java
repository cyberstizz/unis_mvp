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

    // For cron: Top candidates by votes
    @Query("SELECT v.target_id, COUNT(v) as voteCount FROM Vote v WHERE v.jurisdiction.jurisdictionId = :jurisdictionId AND v.interval.intervalId = :intervalId GROUP BY v.target_id ORDER BY voteCount DESC")
    List<Object[]> findTopVoteCounts(@Param("jurisdictionId") UUID jurisdictionId, @Param("intervalId") UUID intervalId);
}