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
    @Query("SELECT COUNT(p) FROM VideoPlay p WHERE p.video.videoId = :videoId AND DATE(p.playedAt) = :date")
    Long countPlaysByDay(@Param("videoId") UUID videoId, @Param("date") LocalDate date);

    @Query("SELECT COUNT(p) FROM VideoPlay p WHERE DATE(p.playedAt) = CURRENT_DATE")
    Long countPlaysToday();
}