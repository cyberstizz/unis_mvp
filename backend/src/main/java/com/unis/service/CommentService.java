package com.unis.service;

import com.unis.dto.CommentDTO;
import com.unis.entity.Comment;
import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.entity.Video;
import com.unis.repository.CommentRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.UserRepository;
import com.unis.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Comments on songs and videos, plus comment likes.
 *
 * COMMENT LIKES live in their own table, comment_likes (see
 * backend/sql/V2026_10k__comment_likes.sql), the same way photo likes do:
 *   - one like per person per comment (primary key), liking twice is a no-op;
 *   - they award NO points and must never be read by anything that scores
 *     songs, artists or listeners (scoring reads the `likes` table);
 *   - every comment this service returns carries likeCount and likedByMe
 *     (likedByMe is false for guests), looked up in one batched query per
 *     request, never one query per comment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository commentRepository;
    private final SongRepository songRepository;
    private final UserRepository userRepository;
    private final VideoRepository videoRepository;
    private final NamedParameterJdbcTemplate namedJdbc;

    // Maximum comment length
    private static final int MAX_COMMENT_LENGTH = 2000;

    // Maximum nesting depth (we only allow 1 level of replies)
    private static final int MAX_DEPTH = 1;

    private static final int MAX_COMMENTS_PER_USER_PER_SONG = 3;

    // Comment ids per like lookup. Keeps the IN (...) list far below the
    // Postgres driver's bind-parameter ceiling on a very busy track.
    private static final int LIKE_LOOKUP_CHUNK = 1000;

    /**
     * Create a new comment or reply
     */
    @Transactional
    public CommentDTO.Response createComment(CommentDTO.CreateRequest request) {
        // Validate content
        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("Comment content cannot be empty");
        }
        
        if (request.getContent().length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("Comment exceeds maximum length of " + MAX_COMMENT_LENGTH + " characters");
        }
 
        // Exactly one of songId / videoId must be provided
        boolean isVideoComment = request.getVideoId() != null;
        if (isVideoComment && request.getSongId() != null) {
            throw new IllegalArgumentException("Provide either songId or videoId, not both");
        }
        if (!isVideoComment && request.getSongId() == null) {
            throw new IllegalArgumentException("Either songId or videoId is required");
        }

        // Get target media
        Song song = null;
        Video video = null;
        if (isVideoComment) {
            video = videoRepository.findById(request.getVideoId())
                    .orElseThrow(() -> new IllegalArgumentException("Video not found"));
        } else {
            song = songRepository.findById(request.getSongId())
                    .orElseThrow(() -> new IllegalArgumentException("Song not found"));
            if (song.isRemoved()) {
                throw new IllegalArgumentException("This song was removed, so it can't take new comments.");
            }
        }
 
        // Get user
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
 
        // Resolve parent comment early (needed for both rate limit and nesting checks)
        Comment parentComment = null;
        if (request.getParentCommentId() != null) {
            parentComment = commentRepository.findActiveById(request.getParentCommentId())
                    .orElseThrow(() -> new IllegalArgumentException("Parent comment not found"));
 
            // Prevent deep nesting - replies can only target top-level comments
            if (parentComment.getParentComment() != null) {
                throw new IllegalArgumentException("Cannot reply to a reply. Please reply to the original comment.");
            }
 
            // Verify parent is on the same media
            if (isVideoComment) {
                if (parentComment.getVideoId() == null || !parentComment.getVideoId().equals(video.getVideoId())) {
                    throw new IllegalArgumentException("Parent comment belongs to a different video");
                }
            } else {
                if (parentComment.getSongId() == null || !parentComment.getSongId().equals(song.getSongId())) {
                    throw new IllegalArgumentException("Parent comment belongs to a different song");
                }
            }
        }
 
        // === RATE LIMIT: 3 comments per user per song/video ===
        long userCommentCount = isVideoComment
                ? commentRepository.countByUserIdAndVideoId(user.getUserId(), video.getVideoId())
                : commentRepository.countByUserIdAndSongId(user.getUserId(), song.getSongId());
 
        if (userCommentCount >= MAX_COMMENTS_PER_USER_PER_SONG) {
            // Over limit — only allowed action is replying under your OWN top-level comment.
            // This covers the scenario:
            //   1. User X posts a top-level comment
            //   2. User Y replies to User X's comment
            //   3. User X wants to respond — they reply under their own top-level comment
            //
            // So: parentComment must exist AND parentComment must belong to the current user.
            boolean isReplyingToOwnComment = parentComment != null
                    && parentComment.getUser().getUserId().equals(user.getUserId());
 
            if (!isReplyingToOwnComment) {
                throw new IllegalArgumentException(
                    "You've reached the comment limit for this " + (isVideoComment ? "video" : "song")
                        + ". You can still reply when someone responds to your comments."
                );
            }
        }
        // === END RATE LIMIT ===
 
        // Create comment
        Comment comment = new Comment();
        if (isVideoComment) {
            comment.setVideo(video);
        } else {
            comment.setSong(song);
        }
        comment.setUser(user);
        comment.setContent(request.getContent().trim());
 
        if (parentComment != null) {
            comment.setParentComment(parentComment);
        }
 
        Comment saved = commentRepository.save(comment);
        log.info("Created comment {} on {} {} by user {}", saved.getCommentId(),
                isVideoComment ? "video" : "song",
                isVideoComment ? video.getVideoId() : song.getSongId(),
                user.getUserId());
        
        return CommentDTO.Response.fromEntity(saved, false);
    }

    /**
     * Get all comments for a song with their replies
     */
    @Transactional(readOnly = true)
    public List<CommentDTO.Response> getCommentsBySongId(UUID songId) {
        return getCommentsBySongId(songId, null);
    }

    /** Same, with likedByMe filled in for this viewer (null = guest). */
    @Transactional(readOnly = true)
    public List<CommentDTO.Response> getCommentsBySongId(UUID songId, UUID viewerId) {
        List<Comment> comments = commentRepository.findTopLevelCommentsBySongId(songId);
        return attachLikes(comments.stream()
                .map(c -> CommentDTO.Response.fromEntity(c, true))
                .collect(Collectors.toList()), viewerId);
    }

    /**
     * Get paginated comments for a song
     */
    @Transactional(readOnly = true)
    public CommentDTO.PagedResponse getCommentsBySongIdPaginated(UUID songId, int page, int size) {
        return getCommentsBySongIdPaginated(songId, page, size, null);
    }

    @Transactional(readOnly = true)
    public CommentDTO.PagedResponse getCommentsBySongIdPaginated(UUID songId, int page, int size, UUID viewerId) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Comment> commentPage = commentRepository.findTopLevelCommentsBySongIdPaginated(songId, pageRequest);

        List<CommentDTO.Response> comments = attachLikes(commentPage.getContent().stream()
                .map(c -> CommentDTO.Response.fromEntity(c, true))
                .collect(Collectors.toList()), viewerId);

        return CommentDTO.PagedResponse.builder()
                .comments(comments)
                .page(page)
                .size(size)
                .totalElements(commentPage.getTotalElements())
                .totalPages(commentPage.getTotalPages())
                .hasNext(commentPage.hasNext())
                .hasPrevious(commentPage.hasPrevious())
                .build();
    }

    /**
     * Get replies for a specific comment
     */
    @Transactional(readOnly = true)
    public List<CommentDTO.Response> getReplies(UUID commentId) {
        return getReplies(commentId, null);
    }

    @Transactional(readOnly = true)
    public List<CommentDTO.Response> getReplies(UUID commentId, UUID viewerId) {
        List<Comment> replies = commentRepository.findRepliesByParentId(commentId);
        return attachLikes(replies.stream()
                .map(c -> CommentDTO.Response.fromEntity(c, false))
                .collect(Collectors.toList()), viewerId);
    }

    /**
     * Update a comment (only by owner)
     */
    @Transactional
    public CommentDTO.Response updateComment(UUID commentId, UUID userId, CommentDTO.UpdateRequest request) {
        Comment comment = commentRepository.findActiveById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found"));

        // Verify ownership
        if (!comment.getUser().getUserId().equals(userId)) {
            throw new SecurityException("You can only edit your own comments");
        }

        // Validate content
        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("Comment content cannot be empty");
        }
        
        if (request.getContent().length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("Comment exceeds maximum length of " + MAX_COMMENT_LENGTH + " characters");
        }

        comment.setContent(request.getContent().trim());
        Comment saved = commentRepository.save(comment);

        log.info("Updated comment {}", commentId);
        // Editing keeps the comment's likes; return them so the app doesn't
        // reset the count to 0 when it swaps in the edited comment.
        return attachLikes(List.of(CommentDTO.Response.fromEntity(saved, false)), userId).get(0);
    }

    /**
     * Delete a comment (soft delete, only by owner or song artist)
     */
    @Transactional
    public void deleteComment(UUID commentId, UUID userId) {
        Comment comment = commentRepository.findActiveById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found"));

        // Allow deletion by comment owner OR the artist who owns the media.
        // Null-safe: song comments have a null video and vice versa.
        boolean isOwner = comment.getUser().getUserId().equals(userId);
        boolean isMediaArtist = false;
        if (comment.getSong() != null && comment.getSong().getArtist() != null) {
            isMediaArtist = comment.getSong().getArtist().getUserId().equals(userId);
        } else if (comment.getVideo() != null && comment.getVideo().getArtist() != null) {
            isMediaArtist = comment.getVideo().getArtist().getUserId().equals(userId);
        }

        if (!isOwner && !isMediaArtist) {
            throw new SecurityException("You can only delete your own comments or comments on your media");
        }

        commentRepository.softDelete(commentId, LocalDateTime.now());
        log.info("Soft deleted comment {} by user {}", commentId, userId);
    }

    /**
     * Get comment count for a song
     */
    @Transactional(readOnly = true)
    public CommentDTO.CountResponse getCommentCount(UUID songId) {
        long total = commentRepository.countBySongId(songId);
        long topLevel = commentRepository.countTopLevelBySongId(songId);
        return new CommentDTO.CountResponse(total, topLevel);
    }

    /**
     * Get a single comment by ID
     */
    @Transactional(readOnly = true)
    public CommentDTO.Response getCommentById(UUID commentId) {
        return getCommentById(commentId, null);
    }

    @Transactional(readOnly = true)
    public CommentDTO.Response getCommentById(UUID commentId, UUID viewerId) {
        Comment comment = commentRepository.findActiveById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found"));
        return attachLikes(List.of(CommentDTO.Response.fromEntity(comment, true)), viewerId).get(0);
    }

      @Transactional(readOnly = true)
    public CommentDTO.UserCommentCountResponse getUserCommentCountForSong(UUID userId, UUID songId) {
        long count = commentRepository.countByUserIdAndSongId(userId, songId);
        return new CommentDTO.UserCommentCountResponse(
            count,
            MAX_COMMENTS_PER_USER_PER_SONG,
            Math.max(0, MAX_COMMENTS_PER_USER_PER_SONG - count),
            count >= MAX_COMMENTS_PER_USER_PER_SONG
        );
    }

    // ========================================================================
    // VIDEO COMMENT METHODS — mirror the song methods above
    // ========================================================================

    /**
     * Get all comments for a video with their replies
     */
    @Transactional(readOnly = true)
    public List<CommentDTO.Response> getCommentsByVideoId(UUID videoId) {
        return getCommentsByVideoId(videoId, null);
    }

    @Transactional(readOnly = true)
    public List<CommentDTO.Response> getCommentsByVideoId(UUID videoId, UUID viewerId) {
        List<Comment> comments = commentRepository.findTopLevelCommentsByVideoId(videoId);
        return attachLikes(comments.stream()
                .map(c -> CommentDTO.Response.fromEntity(c, true))
                .collect(Collectors.toList()), viewerId);
    }

    /**
     * Get paginated comments for a video
     */
    @Transactional(readOnly = true)
    public CommentDTO.PagedResponse getCommentsByVideoIdPaginated(UUID videoId, int page, int size) {
        return getCommentsByVideoIdPaginated(videoId, page, size, null);
    }

    @Transactional(readOnly = true)
    public CommentDTO.PagedResponse getCommentsByVideoIdPaginated(UUID videoId, int page, int size, UUID viewerId) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Comment> commentPage = commentRepository.findTopLevelCommentsByVideoIdPaginated(videoId, pageRequest);

        List<CommentDTO.Response> comments = attachLikes(commentPage.getContent().stream()
                .map(c -> CommentDTO.Response.fromEntity(c, true))
                .collect(Collectors.toList()), viewerId);

        return CommentDTO.PagedResponse.builder()
                .comments(comments)
                .page(page)
                .size(size)
                .totalElements(commentPage.getTotalElements())
                .totalPages(commentPage.getTotalPages())
                .hasNext(commentPage.hasNext())
                .hasPrevious(commentPage.hasPrevious())
                .build();
    }

    /**
     * Get comment count for a video
     */
    @Transactional(readOnly = true)
    public CommentDTO.CountResponse getVideoCommentCount(UUID videoId) {
        long total = commentRepository.countByVideoId(videoId);
        long topLevel = commentRepository.countTopLevelByVideoId(videoId);
        return new CommentDTO.CountResponse(total, topLevel);
    }

    /**
     * Per-user comment count for a video (same 3-comment limit as songs)
     */
    @Transactional(readOnly = true)
    public CommentDTO.UserCommentCountResponse getUserCommentCountForVideo(UUID userId, UUID videoId) {
        long count = commentRepository.countByUserIdAndVideoId(userId, videoId);
        return new CommentDTO.UserCommentCountResponse(
            count,
            MAX_COMMENTS_PER_USER_PER_SONG,
            Math.max(0, MAX_COMMENTS_PER_USER_PER_SONG - count),
            count >= MAX_COMMENTS_PER_USER_PER_SONG
        );
    }

    // ========================================================================
    // COMMENT LIKES — no points, no score updates, no cache to evict
    // (comment lists are never cached, server- or client-side).
    // ========================================================================

    /**
     * Idempotent: liking twice is a no-op. Returns the comment's new like count.
     * Works on replies too. A comment that's deleted, or sits on a removed
     * song, is "not found".
     */
    @Transactional
    public long likeComment(UUID commentId, UUID userId) {
        requireLikeable(commentId);
        namedJdbc.update(
            "INSERT INTO comment_likes (comment_id, user_id, created_at) VALUES (:c, :u, NOW()) " +
            "ON CONFLICT (comment_id, user_id) DO NOTHING",
            new MapSqlParameterSource().addValue("c", commentId).addValue("u", userId));
        return likeCount(commentId);
    }

    /** Idempotent: unliking something you never liked is a no-op. Returns the new count. */
    @Transactional
    public long unlikeComment(UUID commentId, UUID userId) {
        commentRepository.findActiveById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found"));
        namedJdbc.update(
            "DELETE FROM comment_likes WHERE comment_id = :c AND user_id = :u",
            new MapSqlParameterSource().addValue("c", commentId).addValue("u", userId));
        return likeCount(commentId);
    }

    private long likeCount(UUID commentId) {
        Long n = namedJdbc.queryForObject(
            "SELECT COUNT(*) FROM comment_likes WHERE comment_id = :c",
            new MapSqlParameterSource("c", commentId), Long.class);
        return n == null ? 0L : n;
    }

    private void requireLikeable(UUID commentId) {
        Comment comment = commentRepository.findActiveById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found"));
        // A removed song can't be played, liked or commented on — its
        // comments can't collect new likes either.
        if (comment.getSong() != null && comment.getSong().isRemoved()) {
            throw new IllegalArgumentException("Comment not found");
        }
    }

    /**
     * Fills likeCount + likedByMe on these comments AND their nested replies,
     * in one query per {@link #LIKE_LOOKUP_CHUNK} comments. Mutates the DTOs
     * and returns the same list.
     *
     * Needs the comment_likes table: run V2026_10k__comment_likes.sql in
     * Supabase BEFORE deploying this, or every comment list will fail.
     */
    private List<CommentDTO.Response> attachLikes(List<CommentDTO.Response> comments, UUID viewerId) {
        if (comments == null || comments.isEmpty()) return comments;

        Map<UUID, CommentDTO.Response> byId = new HashMap<>();
        for (CommentDTO.Response c : comments) {
            byId.put(c.getCommentId(), c);
            if (c.getReplies() != null) {
                for (CommentDTO.Response r : c.getReplies()) byId.put(r.getCommentId(), r);
            }
        }

        String sql = viewerId == null
            ? "SELECT comment_id, COUNT(*) AS n, FALSE AS mine FROM comment_likes " +
              "WHERE comment_id IN (:ids) GROUP BY comment_id"
            : "SELECT comment_id, COUNT(*) AS n, BOOL_OR(user_id = :viewer) AS mine FROM comment_likes " +
              "WHERE comment_id IN (:ids) GROUP BY comment_id";

        RowCallbackHandler fill = rs -> {
            CommentDTO.Response dto = byId.get(rs.getObject("comment_id", UUID.class));
            if (dto != null) {
                dto.setLikeCount(rs.getLong("n"));
                dto.setLikedByMe(rs.getBoolean("mine"));
            }
        };

        List<UUID> ids = new ArrayList<>(byId.keySet());
        for (int from = 0; from < ids.size(); from += LIKE_LOOKUP_CHUNK) {
            List<UUID> chunk = ids.subList(from, Math.min(from + LIKE_LOOKUP_CHUNK, ids.size()));
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("ids", chunk);
            if (viewerId != null) params.addValue("viewer", viewerId);
            namedJdbc.query(sql, params, fill);
        }
        return comments;
    }

}