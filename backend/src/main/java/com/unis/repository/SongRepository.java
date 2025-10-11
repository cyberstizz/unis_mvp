package com.unis.repository;

import com.unis.entity.Song;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface SongRepository extends JpaRepository<Song, UUID> {
    @Query("SELECT s FROM Song s JOIN s.artist a WHERE a.jurisdiction.jurisdictionId = :jurisdictionId ORDER BY s.score DESC")
    List<Song> findTopByJurisdiction(@Param("jurisdictionId") UUID jurisdictionId);

    // For plays today (native for count)
    @Query(value = "SELECT COUNT(*) FROM song_plays sp WHERE sp.song_id = :songId AND DATE(sp.played_at) = CURRENT_DATE", nativeQuery = true)
    Long countPlaysToday(@Param("songId") UUID songId);

    @Modifying
    @Query("UPDATE Song s SET s.score = :score, s.level = :level WHERE s.songId = :id")
    void updateSongScoreAndLevel(@Param("id") UUID id, @Param("score") int score, @Param("level") String level);

    @Query(value = "SELECT s.song_id, " +
                   "COALESCE((SELECT COUNT(sp.play_id) * 1 FROM song_plays sp WHERE sp.song_id = s.song_id), 0) + " +
                   "COALESCE((SELECT COUNT(v.vote_id) * 3 FROM votes v WHERE v.target_type = 'song' AND v.target_id = s.song_id), 0) + " +
                   "COALESCE((SELECT COUNT(l.like_id) * 2 FROM likes l WHERE l.media_type = 'song' AND l.media_id = s.song_id), 0) + " +
                   "COALESCE((SELECT COUNT(a.award_id) * 10 FROM awards a WHERE a.target_type = 'song' AND a.target_id = s.song_id), 0) + " +
                   "s.score as new_score " +
                   "FROM songs s " +
                   "GROUP BY s.song_id", nativeQuery = true)
    List<Object[]> computeSongScores();

    // Additional: Increment score (for events)
    @Modifying
    @Query("UPDATE Song s SET s.score = s.score + :increment WHERE s.songId = :id")
    void incrementScore(@Param("id") UUID id, @Param("increment") int increment);
}