package com.unis.repository;

import com.unis.entity.SongPlay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.UUID;

@Repository
public interface SongPlayRepository extends JpaRepository<SongPlay, UUID> {
    @Query("SELECT COUNT(p) FROM SongPlay p WHERE p.song.songId = :songId AND DATE(p.playedAt) = :date")
    Long countPlaysByDay(@Param("songId") UUID songId, @Param("date") LocalDate date);

    @Query("SELECT COUNT(p) FROM SongPlay p WHERE DATE(p.playedAt) = CURRENT_DATE")
    Long countPlaysToday();
}