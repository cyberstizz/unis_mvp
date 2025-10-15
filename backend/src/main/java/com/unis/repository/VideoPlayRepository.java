package com.unis.repository;

import com.unis.entity.VideoPlay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.UUID;

@Repository
public interface VideoPlayRepository extends JpaRepository<VideoPlay, UUID> {
    @Query(value = "SELECT COUNT(*) FROM video_plays p WHERE p.video_id = :videoId AND DATE(p.played_at) = :date", nativeQuery = true)
    Long countPlaysByDay(@Param("videoId") UUID videoId, @Param("date") LocalDate date);

    @Query(value = "SELECT COUNT(*) FROM video_plays p WHERE DATE(p.played_at) = CURRENT_DATE", nativeQuery = true)
    Long countPlaysToday();
}