package com.unis.repository;

import com.unis.entity.Video;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface VideoRepository extends JpaRepository<Video, UUID> {
    // Top by jurisdiction with hierarchy (recursive CTE for children)
    @Query(value = "WITH RECURSIVE jurisdiction_tree AS ( " +
               "  SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId " +
               "  UNION ALL " +
               "  SELECT j.jurisdiction_id FROM jurisdictions j JOIN jurisdiction_tree jt ON j.parent_jurisdiction_id = jt.jurisdiction_id " +
               ") " +
               "SELECT v.* FROM videos v JOIN users u ON v.artist_id = u.user_id JOIN jurisdiction_tree jt ON u.jurisdiction_id = jt.jurisdiction_id " +
               "ORDER BY v.score DESC LIMIT :limit", nativeQuery = true)
    List<Video> findTopByJurisdictionWithHierarchy(@Param("jurisdictionId") UUID jurisdictionId, @Param("limit") int limit);

    // For plays today (native for count)
    @Query(value = "SELECT COUNT(*) FROM video_plays vp WHERE vp.video_id = :videoId AND DATE(vp.played_at) = CURRENT_DATE", nativeQuery = true)
    Long countPlaysToday(@Param("videoId") UUID videoId);

    @Modifying
    @Query("UPDATE Video v SET v.score = :score, v.level = :level WHERE v.videoId = :id")
    void updateVideoScoreAndLevel(@Param("id") UUID id, @Param("score") int score, @Param("level") String level);

    @Query(value = "SELECT v.video_id, " +
                   "COALESCE((SELECT COUNT(vp.play_id) * 1 FROM video_plays vp WHERE vp.video_id = v.video_id), 0) + " +
                   "COALESCE((SELECT COUNT(vt.vote_id) * 3 FROM votes vt WHERE vt.target_type = 'video' AND vt.target_id = v.video_id), 0) + " +
                   "COALESCE((SELECT COUNT(l.like_id) * 2 FROM likes l WHERE l.media_type = 'video' AND l.media_id = v.video_id), 0) + " +
                   "COALESCE((SELECT COUNT(a.award_id) * 10 FROM awards a WHERE a.target_type = 'video' AND a.target_id = v.video_id), 0) + " +
                   "v.score as new_score " +
                   "FROM videos v " +
                   "GROUP BY v.video_id", nativeQuery = true)
    List<Object[]> computeVideoScores();

    // Add for artist dashboard (page 7)
    @Query("SELECT v FROM Video v WHERE v.artist.userId = :artistId ORDER BY v.createdAt DESC")
    List<Video> findByArtistId(@Param("artistId") UUID artistId);

    @Query("SELECT v FROM Video v JOIN v.artist a WHERE a.jurisdiction.jurisdictionId = :jurisdictionId ORDER BY v.score DESC")
    List<Video> findTopByJurisdiction(@Param("jurisdictionId") UUID jurisdictionId);

    // Increment score (for events)
    @Modifying
    @Query("UPDATE Video v SET v.score = v.score + :increment WHERE v.videoId = :id")
    void incrementScore(@Param("id") UUID id, @Param("increment") int increment);
}