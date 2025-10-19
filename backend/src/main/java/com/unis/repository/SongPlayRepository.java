package com.unis.repository;

import com.unis.entity.SongPlay;
import org.springframework.data.jpa.repository.JpaRepository;
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

    // Trending songs by jurisdiction (page 8; top by plays today DESC)
    @Query(value = "SELECT sp.song_id, COUNT(*) as play_count FROM song_plays sp JOIN songs s ON sp.song_id = s.song_id JOIN users u ON s.artist_id = u.user_id WHERE u.jurisdiction_id = :jurisdictionId AND DATE(sp.played_at) = CURRENT_DATE GROUP BY sp.song_id ORDER BY play_count DESC LIMIT :limit", nativeQuery = true)
    List<Object[]> findTrendingByJurisdiction(@Param("jurisdictionId") UUID jurisdictionId, @Param("limit") int limit);
}