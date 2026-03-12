package com.unis.controller;

import com.unis.entity.DmcaClaim;
import com.unis.entity.DmcaCounterNotice;
import com.unis.entity.Comment;
import com.unis.repository.CommentRepository;
import com.unis.service.DmcaService;
import com.unis.service.ModerationService;
import com.unis.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminModerationController {

    @Autowired
    private DmcaService dmcaService;

    @Autowired
    private ModerationService moderationService;

    @Autowired
    private CommentRepository commentRepository;

    // ========== DMCA CLAIMS ==========

    /**
     * GET /api/v1/admin/dmca/claims?status=&page=0&size=20
     */
    @GetMapping("/dmca/claims")
    public ResponseEntity<Page<DmcaClaim>> getClaims(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(dmcaService.getClaims(status, page, size));
    }

    /**
     * GET /api/v1/admin/dmca/claims/{claimId}
     */
    @GetMapping("/dmca/claims/{claimId}")
    public ResponseEntity<?> getClaimDetail(@PathVariable UUID claimId) {
        try {
            DmcaClaim claim = dmcaService.getClaimById(claimId);
            DmcaCounterNotice counterNotice = dmcaService.getCounterNoticeForClaim(claimId);

            Map<String, Object> detail = new java.util.HashMap<>();
            detail.put("claim", claim);
            detail.put("counterNotice", counterNotice);
            detail.put("actionHistory", moderationService.getActionsForTarget("dmca_claim", claimId));

            return ResponseEntity.ok(detail);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * PATCH /api/v1/admin/dmca/claims/{claimId}/status
     * Update claim status (reviewing, upheld, rejected)
     */
    @PatchMapping("/dmca/claims/{claimId}/status")
    public ResponseEntity<?> updateClaimStatus(
            @PathVariable UUID claimId,
            @RequestBody Map<String, String> request) {
        try {
            UUID adminUserId = SecurityUtils.getAuthenticatedUserId();
            String newStatus = request.get("status");
            String notes = request.get("notes");

            if (newStatus == null || newStatus.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Status is required"));
            }

            DmcaClaim updated = dmcaService.updateClaimStatus(claimId, newStatus, adminUserId, notes);
            return ResponseEntity.ok(updated);

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/v1/admin/dmca/claims/{claimId}/takedown
     * Execute content removal for an upheld claim
     */
    @PostMapping("/dmca/claims/{claimId}/takedown")
    public ResponseEntity<?> executeTakedown(@PathVariable UUID claimId) {
        try {
            UUID adminUserId = SecurityUtils.getAuthenticatedUserId();
            dmcaService.executeTakedown(claimId, adminUserId);
            return ResponseEntity.ok(Map.of("message", "Content takedown executed"));

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ========== COMMENT MODERATION ==========

    /**
     * GET /api/v1/admin/comments/recent?page=0&size=20
     */
   @GetMapping("/comments/recent")
    public ResponseEntity<?> getRecentComments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Comment> commentPage = commentRepository.findRecentComments(
                PageRequest.of(page, size));

        // Transform to maps because Comment.song has @JsonBackReference
        List<Map<String, Object>> content = commentPage.getContent().stream().map(c -> {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("commentId", c.getCommentId());
            map.put("content", c.getContent());
            map.put("createdAt", c.getCreatedAt());
            map.put("user", Map.of(
                "userId", c.getUser().getUserId(),
                "username", c.getUser().getUsername()
            ));
            map.put("song", Map.of(
                "songId", c.getSong().getSongId(),
                "title", c.getSong().getTitle()
            ));
            return map;
        }).collect(java.util.stream.Collectors.toList());

        Map<String, Object> response = new java.util.HashMap<>();
        response.put("content", content);
        response.put("totalPages", commentPage.getTotalPages());
        response.put("totalElements", commentPage.getTotalElements());

        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/v1/admin/comments/{commentId}
     * Admin soft-delete with audit logging
     */
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<?> adminDeleteComment(
            @PathVariable UUID commentId,
            @RequestBody(required = false) Map<String, String> request) {
        try {
            UUID adminUserId = SecurityUtils.getAuthenticatedUserId();
            String reason = (request != null) ? request.get("reason") : "Removed by moderator";

            moderationService.adminDeleteComment(commentId, adminUserId, reason);
            return ResponseEntity.ok(Map.of("message", "Comment deleted"));

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}