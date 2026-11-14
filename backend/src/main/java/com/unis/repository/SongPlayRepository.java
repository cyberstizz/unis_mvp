package com.unis.repository;

import com.unis.entity.SongPlay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface SongPlayRepository extends JpaRepository<SongPlay, UUID> {
    @Query(value = "SELECT COUNT(*) FROM song_plays p WHERE p.song_id = :songId AND DATE(p.played_at) = :date", nativeQuery = true)
    Long countPlaysByDay(@Param("songId") UUID songId, @Param("date") LocalDate date);

    @Query(value = "SELECT COUNT(*) FROM song_plays p WHERE DATE(p.played_at) = CURRENT_DATE", nativeQuery = true)
    Long countPlaysToday();

    @Modifying
    @Query("DELETE FROM SongPlay sp WHERE sp.user.userId = :userId")
    void deleteByUserUserId(@Param("userId") UUID userId);

    @Query(value = "SELECT COUNT(*) FROM song_plays WHERE song_id = :songId", nativeQuery = true)
    Long countTotalPlaysBySongId(@Param("songId") UUID songId);
}