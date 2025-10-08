package com.unis.repository;

import com.unis.entity.Video;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface VideoRepository extends JpaRepository<Video, UUID> {
    @Query("SELECT v FROM Video v JOIN v.artist a WHERE a.jurisdiction.jurisdictionId = :jurisdictionId ORDER BY v.score DESC")
    List<Video> findTopByJurisdiction(@Param("jurisdictionId") UUID jurisdictionId);

    // For plays today (native for count)
    @Query(value = "SELECT COUNT(*) FROM video_plays vp WHERE vp.video_id = :videoId AND DATE(vp.played_at) = CURRENT_DATE", nativeQuery = true)
    Long countPlaysToday(@Param("videoId") UUID videoId);
}