package com.unis.repository;

import com.unis.entity.Award;
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
    @Query("SELECT a FROM Award a WHERE a.jurisdiction.jurisdictionId = :jurisdictionId AND a.interval.intervalId = :intervalId AND a.awardDate BETWEEN :start AND :end ORDER BY a.votesCount DESC")
    List<Award> findTopByPeriod(@Param("jurisdictionId") UUID jurisdictionId, @Param("intervalId") UUID intervalId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    // For cron: Top candidates by votes (native for GROUP BY/alias)
    @Query(value = "SELECT v.target_id, COUNT(v) as voteCount FROM votes v WHERE v.jurisdiction_id = :jurisdictionId AND v.interval_id = :intervalId GROUP BY v.target_id ORDER BY voteCount DESC", nativeQuery = true)
    List<Object[]> findTopVoteCounts(@Param("jurisdictionId") UUID jurisdictionId, @Param("intervalId") UUID intervalId);

    // Increment engagement for ongoing awards on vote (native UPDATE—no alias in SET)
    @Modifying
    @Query(value = "UPDATE awards SET votes_count = votes_count + 1, engagement_score = engagement_score + 1 WHERE target_type = :targetType AND target_id = :targetId AND jurisdiction_id = :jurisdictionId AND interval_id = :intervalId", nativeQuery = true)
    void incrementAwardEngagement(@Param("targetType") String targetType, @Param("targetId") UUID targetId, @Param("jurisdictionId") UUID jurisdictionId, @Param("intervalId") UUID intervalId);

    @Query(value = """
    SELECT a.award_id, a.target_type, a.target_id, a.votes_count, a.award_date,
            CASE WHEN a.target_type = 'artist' THEN u.username ELSE s.title END as title,
            CASE WHEN a.target_type = 'artist' THEN u.username ELSE a.username END as artist,  // Fallback artist name
            CASE WHEN a.target_type = 'artist' THEN u.photo_url ELSE s.artwork_url END as artwork,
            a.caption
    FROM awards a
    LEFT JOIN users u ON a.target_type = 'artist' AND a.target_id = u.user_id
    LEFT JOIN songs s ON a.target_type = 'song' AND a.target_id = s.song_id
    LEFT JOIN genres g ON a.genre_id = g.genre_id
    WHERE a.jurisdiction_id = :jurisdictionId AND a.award_date = :date
        AND (:genreId IS NULL OR g.genre_id = :genreId)
        AND (:intervalId IS NULL OR a.interval_id = :intervalId)
    ORDER BY a.votes_count DESC
    """, nativeQuery = true)
    List<Object[]> findMilestonesByDate(@Param("jurisdictionId") UUID jurisdictionId, @Param("date") LocalDate date, @Param("genreId") UUID genreId, @Param("intervalId") UUID intervalId);

}