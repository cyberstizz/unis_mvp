package com.unis.repository;

import com.unis.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID> {

    // Get all top-level comments for a song (not replies), ordered by newest first
    @Query("SELECT c FROM Comment c JOIN FETCH c.user u LEFT JOIN FETCH u.jurisdiction WHERE c.song.songId = :songId AND c.parentComment IS NULL AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<Comment> findTopLevelCommentsBySongId(@Param("songId") UUID songId);

    // Get paginated top-level comments for a song
    @Query("SELECT c FROM Comment c JOIN FETCH c.user u LEFT JOIN FETCH u.jurisdiction WHERE c.song.songId = :songId AND c.parentComment IS NULL AND c.deletedAt IS NULL")
    Page<Comment> findTopLevelCommentsBySongIdPaginated(@Param("songId") UUID songId, Pageable pageable);
    // Get all replies to a specific comment
    @Query("SELECT c FROM Comment c JOIN FETCH c.user u LEFT JOIN FETCH u.jurisdiction WHERE c.parentComment.commentId = :parentId AND c.deletedAt IS NULL ORDER BY c.createdAt ASC")
    List<Comment> findRepliesByParentId(@Param("parentId") UUID parentId);

    // Get comment count for a song (including replies)
    @Query("SELECT COUNT(c) FROM Comment c WHERE c.song.songId = :songId AND c.deletedAt IS NULL")
    long countBySongId(@Param("songId") UUID songId);

    // Get top-level comment count for a song
    @Query("SELECT COUNT(c) FROM Comment c WHERE c.song.songId = :songId AND c.parentComment IS NULL AND c.deletedAt IS NULL")
    long countTopLevelBySongId(@Param("songId") UUID songId);

    // Get all comments by a user
    @Query("SELECT c FROM Comment c WHERE c.user.userId = :userId AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<Comment> findByUserId(@Param("userId") UUID userId);

    // Find a comment by ID (respecting soft delete)
    @Query("SELECT c FROM Comment c WHERE c.commentId = :commentId AND c.deletedAt IS NULL")
    Optional<Comment> findActiveById(@Param("commentId") UUID commentId);

    // Soft delete a comment
    @Modifying
    @Query("UPDATE Comment c SET c.deletedAt = :now WHERE c.commentId = :commentId")
    void softDelete(@Param("commentId") UUID commentId, @Param("now") LocalDateTime now);

    // Check if a user owns a comment
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM Comment c WHERE c.commentId = :commentId AND c.user.userId = :userId AND c.deletedAt IS NULL")
    boolean isCommentOwner(@Param("commentId") UUID commentId, @Param("userId") UUID userId);

    // Get recent comments across all media (for admin moderation).
    // LEFT JOIN FETCH: video comments have a null song (and vice versa), so an
    // inner JOIN FETCH would silently drop them from the moderation queue.
    @Query(value = "SELECT c FROM Comment c JOIN FETCH c.user LEFT JOIN FETCH c.song LEFT JOIN FETCH c.video WHERE c.deletedAt IS NULL ORDER BY c.createdAt DESC",
           countQuery = "SELECT COUNT(c) FROM Comment c WHERE c.deletedAt IS NULL")
    Page<Comment> findRecentComments(Pageable pageable);

    // === CommentRepository.java — ADD THESE TWO METHODS ===
    // Add these to your existing CommentRepository interface.

    // Count all comments (top-level + replies) by a user on a specific song
    @Query("SELECT COUNT(c) FROM Comment c WHERE c.user.userId = :userId AND c.song.songId = :songId AND c.deletedAt IS NULL")
    long countByUserIdAndSongId(@Param("userId") UUID userId, @Param("songId") UUID songId);

    // ========================================================================
    // VIDEO COMMENT QUERIES — mirror the song queries above
    // ========================================================================

    // Get all top-level comments for a video (not replies), ordered by newest first
    @Query("SELECT c FROM Comment c JOIN FETCH c.user u LEFT JOIN FETCH u.jurisdiction WHERE c.video.videoId = :videoId AND c.parentComment IS NULL AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<Comment> findTopLevelCommentsByVideoId(@Param("videoId") UUID videoId);

    // Get paginated top-level comments for a video
    @Query("SELECT c FROM Comment c JOIN FETCH c.user u LEFT JOIN FETCH u.jurisdiction WHERE c.video.videoId = :videoId AND c.parentComment IS NULL AND c.deletedAt IS NULL")
    Page<Comment> findTopLevelCommentsByVideoIdPaginated(@Param("videoId") UUID videoId, Pageable pageable);

    // Get comment count for a video (including replies)
    @Query("SELECT COUNT(c) FROM Comment c WHERE c.video.videoId = :videoId AND c.deletedAt IS NULL")
    long countByVideoId(@Param("videoId") UUID videoId);

    // Get top-level comment count for a video
    @Query("SELECT COUNT(c) FROM Comment c WHERE c.video.videoId = :videoId AND c.parentComment IS NULL AND c.deletedAt IS NULL")
    long countTopLevelByVideoId(@Param("videoId") UUID videoId);

    // Count all comments (top-level + replies) by a user on a specific video
    @Query("SELECT COUNT(c) FROM Comment c WHERE c.user.userId = :userId AND c.video.videoId = :videoId AND c.deletedAt IS NULL")
    long countByUserIdAndVideoId(@Param("userId") UUID userId, @Param("videoId") UUID videoId);

}