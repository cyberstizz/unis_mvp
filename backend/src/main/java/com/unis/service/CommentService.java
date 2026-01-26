package com.unis.service;

import com.unis.dto.CommentDTO;
import com.unis.entity.Comment;
import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.repository.CommentRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository commentRepository;
    private final SongRepository songRepository;
    private final UserRepository userRepository;

    // Maximum comment length
    private static final int MAX_COMMENT_LENGTH = 2000;
    
    // Maximum nesting depth (we only allow 1 level of replies)
    private static final int MAX_DEPTH = 1;

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

        // Get song
        Song song = songRepository.findById(request.getSongId())
                .orElseThrow(() -> new IllegalArgumentException("Song not found"));

        // Get user
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Create comment
        Comment comment = new Comment();
        comment.setSong(song);
        comment.setUser(user);
        comment.setContent(request.getContent().trim());

        // Handle reply
        if (request.getParentCommentId() != null) {
            Comment parentComment = commentRepository.findActiveById(request.getParentCommentId())
                    .orElseThrow(() -> new IllegalArgumentException("Parent comment not found"));
            
            // Prevent deep nesting - replies can only be on top-level comments
            if (parentComment.getParentComment() != null) {
                throw new IllegalArgumentException("Cannot reply to a reply. Please reply to the original comment.");
            }
            
            // Verify parent is on the same song
            if (!parentComment.getSong().getSongId().equals(song.getSongId())) {
                throw new IllegalArgumentException("Parent comment belongs to a different song");
            }
            
            comment.setParentComment(parentComment);
        }

        Comment saved = commentRepository.save(comment);
        log.info("Created comment {} on song {} by user {}", saved.getCommentId(), song.getSongId(), user.getUserId());
        
        return CommentDTO.Response.fromEntity(saved, false);
    }

    /**
     * Get all comments for a song with their replies
     */
    @Transactional(readOnly = true)
    public List<CommentDTO.Response> getCommentsBySongId(UUID songId) {
        List<Comment> comments = commentRepository.findTopLevelCommentsBySongId(songId);
        return comments.stream()
                .map(c -> CommentDTO.Response.fromEntity(c, true))
                .collect(Collectors.toList());
    }

    /**
     * Get paginated comments for a song
     */
    @Transactional(readOnly = true)
    public CommentDTO.PagedResponse getCommentsBySongIdPaginated(UUID songId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Comment> commentPage = commentRepository.findTopLevelCommentsBySongIdPaginated(songId, pageRequest);
        
        List<CommentDTO.Response> comments = commentPage.getContent().stream()
                .map(c -> CommentDTO.Response.fromEntity(c, true))
                .collect(Collectors.toList());

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
        List<Comment> replies = commentRepository.findRepliesByParentId(commentId);
        return replies.stream()
                .map(c -> CommentDTO.Response.fromEntity(c, false))
                .collect(Collectors.toList());
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
        return CommentDTO.Response.fromEntity(saved, false);
    }

    /**
     * Delete a comment (soft delete, only by owner or song artist)
     */
    @Transactional
    public void deleteComment(UUID commentId, UUID userId) {
        Comment comment = commentRepository.findActiveById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found"));

        // Allow deletion by comment owner OR song artist
        boolean isOwner = comment.getUser().getUserId().equals(userId);
        boolean isSongArtist = comment.getSong().getArtist().getUserId().equals(userId);
        
        if (!isOwner && !isSongArtist) {
            throw new SecurityException("You can only delete your own comments or comments on your songs");
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
        Comment comment = commentRepository.findActiveById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found"));
        return CommentDTO.Response.fromEntity(comment, true);
    }
}