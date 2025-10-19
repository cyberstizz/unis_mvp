package com.unis.repository;

import com.unis.entity.VideoPlay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface VideoPlayRepository extends JpaRepository<VideoPlay, UUID> {
    @Query(value = "SELECT COUNT(*) FROM video_plays p WHERE p.video_id = :videoId AND DATE(p.played_at) = :date", nativeQuery = true)
    Long countPlaysByDay(@Param("videoId") UUID videoId, @Param("date") LocalDate date);

    @Query(value = "SELECT COUNT(*) FROM video_plays p WHERE DATE(p.played_at) = CURRENT_DATE", nativeQuery = true)
    Long countPlaysToday();

    // Trending videos by jurisdiction (page 8; top by plays today DESC)
    @Query(value = "SELECT vp.video_id, COUNT(*) as play_count FROM video_plays vp JOIN videos v ON vp.video_id = v.video_id JOIN users u ON v.artist_id = u.user_id WHERE u.jurisdiction_id = :jurisdictionId AND DATE(vp.played_at) = CURRENT_DATE GROUP BY vp.video_id ORDER BY play_count DESC LIMIT :limit", nativeQuery = true)
    List<Object[]> findTrendingByJurisdiction(@Param("jurisdictionId") UUID jurisdictionId, @Param("limit") int limit);
}