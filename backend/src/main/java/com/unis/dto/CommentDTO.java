package com.unis.dto;

import com.unis.entity.Comment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class CommentDTO {

    // Request DTO for creating a comment
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private UUID songId;   // set for song comments
        private UUID videoId;  // set for video comments (exactly one of songId/videoId)
        private UUID userId;
        private UUID parentCommentId; // null for top-level comments
        private String content;
    }

    // Request DTO for updating a comment
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String content;
    }

    // Response DTO for a single comment
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private UUID commentId;
        private UUID songId;
        private UUID videoId;
        private UUID parentCommentId;
        private String content;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private boolean isReply;
        private int replyCount;
        private String userJurisdictionName;

        // User info
        private UUID userId;
        private String username;
        private String userPhotoUrl;
        
        // Nested replies (only populated for top-level comments)
        private List<Response> replies;

        // Static factory method to convert entity to DTO
        public static Response fromEntity(Comment comment, boolean includeReplies) {
            ResponseBuilder builder = Response.builder()
                    .commentId(comment.getCommentId())
                    .songId(comment.getSongId())
                    .videoId(comment.getVideoId())
                    .parentCommentId(comment.getParentCommentId())
                    .content(comment.getContent())
                    .createdAt(comment.getCreatedAt())
                    .updatedAt(comment.getUpdatedAt())
                    .isReply(comment.isReply())
                    .replyCount(comment.getReplyCount())
                    .userId(comment.getUser().getUserId())
                    .username(comment.getUser().getUsername())
                    .userPhotoUrl(comment.getUser().getPhotoUrl())
                    .userJurisdictionName(comment.getUser().getJurisdiction() != null ? comment.getUser().getJurisdiction().getName() : null);

            if (includeReplies && comment.getReplies() != null && !comment.getReplies().isEmpty()) {
                builder.replies(comment.getReplies().stream()
                        .map(reply -> Response.fromEntity(reply, false)) // Don't recursively load replies of replies
                        .collect(Collectors.toList()));
            }

            return builder.build();
        }
    }

    // Response for comment count
    @Data
    @AllArgsConstructor
    public static class CountResponse {
        private long totalCount;
        private long topLevelCount;
    }

    // Paginated response
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PagedResponse {
        private List<Response> comments;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private boolean hasNext;
        private boolean hasPrevious;
    }

    @Data
    @AllArgsConstructor
    public static class UserCommentCountResponse {
        private long count;
        private int limit;
        private long remaining;
        private boolean limitReached;
    }
}