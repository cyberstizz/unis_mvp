package com.unis.controller;

import com.unis.dto.CommentDTO;
import com.unis.util.SecurityUtils;
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
     * C6 FIX: userId comes from JWT, not from request body
     */
    @PostMapping
    public ResponseEntity<?> createComment(@RequestBody CommentDTO.CreateRequest request) {
        try {
            // C6 FIX: Override whatever userId is in the request with the authenticated user
            UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();
            request.setUserId(authenticatedUserId);

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

    /**
     * PATCH /api/v1/comments/{commentId}
     * Update a comment (only by owner)
     * C6 FIX: userId from JWT, not query param
     */
    @PatchMapping("/{commentId}")
    public ResponseEntity<?> updateComment(
            @PathVariable UUID commentId,
            @RequestParam(required = false) UUID userId,
            @RequestBody CommentDTO.UpdateRequest request) {
        try {
            // C6 FIX: Use authenticated userId instead of query param
            UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();
            CommentDTO.Response response = commentService.updateComment(commentId, authenticatedUserId, request);
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

    /**
     * DELETE /api/v1/comments/{commentId}
     * Delete a comment (by owner or song artist)
     * C6 FIX: userId from JWT, not query param
     */
    @DeleteMapping("/{commentId}")
    public ResponseEntity<?> deleteComment(
            @PathVariable UUID commentId,
            @RequestParam(required = false) UUID userId) {
        try {
            // C6 FIX: Use authenticated userId instead of query param
            UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();
            commentService.deleteComment(commentId, authenticatedUserId);
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

    @GetMapping("/song/{songId}/user-count")
    public ResponseEntity<?> getUserCommentCount(@PathVariable UUID songId) {
        try {
            UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();
            CommentDTO.UserCommentCountResponse count = commentService.getUserCommentCountForSong(authenticatedUserId, songId);
            return ResponseEntity.ok(count);
        } catch (Exception e) {
            // If not authenticated, return 0 (guest users can't comment anyway)
            return ResponseEntity.ok(Map.of("count", 0, "limit", 3, "remaining", 3, "limitReached", false));
        }
    }

}