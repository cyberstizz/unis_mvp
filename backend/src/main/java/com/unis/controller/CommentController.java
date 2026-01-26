package com.unis.controller;

import com.unis.dto.CommentDTO;
import com.unis.service.CommentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;


    /**
     * POST /api/v1/comments
     * Create a new comment or reply
     */
    @PostMapping
    public ResponseEntity<?> createComment(@RequestBody CommentDTO.CreateRequest request) {
        try {
            CommentDTO.Response response = commentService.createComment(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create comment: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating comment", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create comment"));
        }
    }


    /**
     * GET /api/v1/comments/song/{songId}
     * Get all comments for a song (with nested replies)
     */
    @GetMapping("/song/{songId}")
    public ResponseEntity<?> getCommentsBySong(@PathVariable UUID songId) {
        try {
            List<CommentDTO.Response> comments = commentService.getCommentsBySongId(songId);
            return ResponseEntity.ok(comments);
        } catch (Exception e) {
            log.error("Error fetching comments for song {}", songId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch comments"));
        }
    }

    /**
     * GET /api/v1/comments/song/{songId}/paginated?page=0&size=10
     * Get paginated comments for a song
     */
    @GetMapping("/song/{songId}/paginated")
    public ResponseEntity<?> getCommentsBySongPaginated(
            @PathVariable UUID songId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            CommentDTO.PagedResponse response = commentService.getCommentsBySongIdPaginated(songId, page, size);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching paginated comments for song {}", songId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch comments"));
        }
    }

    /**
     * GET /api/v1/comments/{commentId}
     * Get a single comment by ID
     */
    @GetMapping("/{commentId}")
    public ResponseEntity<?> getComment(@PathVariable UUID commentId) {
        try {
            CommentDTO.Response comment = commentService.getCommentById(commentId);
            return ResponseEntity.ok(comment);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error fetching comment {}", commentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch comment"));
        }
    }

    /**
     * GET /api/v1/comments/{commentId}/replies
     * Get replies to a specific comment
     */
    @GetMapping("/{commentId}/replies")
    public ResponseEntity<?> getReplies(@PathVariable UUID commentId) {
        try {
            List<CommentDTO.Response> replies = commentService.getReplies(commentId);
            return ResponseEntity.ok(replies);
        } catch (Exception e) {
            log.error("Error fetching replies for comment {}", commentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch replies"));
        }
    }

    /**
     * GET /api/v1/comments/song/{songId}/count
     * Get comment count for a song
     */
    @GetMapping("/song/{songId}/count")
    public ResponseEntity<?> getCommentCount(@PathVariable UUID songId) {
        try {
            CommentDTO.CountResponse count = commentService.getCommentCount(songId);
            return ResponseEntity.ok(count);
        } catch (Exception e) {
            log.error("Error fetching comment count for song {}", songId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch comment count"));
        }
    }

    // ========== UPDATE ==========

    /**
     * PATCH /api/v1/comments/{commentId}?userId={userId}
     * Update a comment (only by owner)
     */
    @PatchMapping("/{commentId}")
    public ResponseEntity<?> updateComment(
            @PathVariable UUID commentId,
            @RequestParam UUID userId,
            @RequestBody CommentDTO.UpdateRequest request) {
        try {
            CommentDTO.Response response = commentService.updateComment(commentId, userId, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating comment {}", commentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update comment"));
        }
    }

    // ========== DELETE ==========

    /**
     * DELETE /api/v1/comments/{commentId}?userId={userId}
     * Delete a comment (by owner or song artist)
     */
    @DeleteMapping("/{commentId}")
    public ResponseEntity<?> deleteComment(
            @PathVariable UUID commentId,
            @RequestParam UUID userId) {
        try {
            commentService.deleteComment(commentId, userId);
            return ResponseEntity.ok(Map.of("success", true, "message", "Comment deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error deleting comment {}", commentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete comment"));
        }
    }
}