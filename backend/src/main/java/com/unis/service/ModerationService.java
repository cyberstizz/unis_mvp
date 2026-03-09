package com.unis.service;

import com.unis.entity.ModerationAction;
import com.unis.entity.User;
import com.unis.repository.ModerationActionRepository;
import com.unis.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ModerationService {

    @Autowired
    private ModerationActionRepository moderationActionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.unis.repository.CommentRepository commentRepository;

    /**
     * Log any admin/moderator action for audit trail.
     * Called by other services — never bypassed.
     */
    public ModerationAction logAction(UUID performedByUserId, String actionType,
                                       String targetType, UUID targetId,
                                       String reason, String details) {
        User performer = userRepository.findById(performedByUserId)
                .orElseThrow(() -> new RuntimeException("Admin user not found: " + performedByUserId));

        ModerationAction action = ModerationAction.builder()
                .performedBy(performer)
                .actionType(actionType)
                .targetType(targetType)
                .targetId(targetId)
                .reason(reason)
                .details(details)
                .build();

        return moderationActionRepository.save(action);
    }

    /**
     * Admin delete a comment (soft delete + audit log)
     */
    public void adminDeleteComment(UUID commentId, UUID adminUserId, String reason) {
        commentRepository.softDelete(commentId, java.time.LocalDateTime.now());
        logAction(adminUserId, "comment_deleted", "comment", commentId, reason, null);
    }

    /**
     * Get paginated audit log (all actions)
     */
    @Transactional(readOnly = true)
    public Page<ModerationAction> getAuditLog(int page, int size) {
        return moderationActionRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(page, size));
    }

    /**
     * Get audit log filtered by admin
     */
    @Transactional(readOnly = true)
    public Page<ModerationAction> getAuditLogByAdmin(UUID adminId, int page, int size) {
        return moderationActionRepository.findByPerformedBy(adminId,
                PageRequest.of(page, size));
    }

    /**
     * Get audit log filtered by action type
     */
    @Transactional(readOnly = true)
    public Page<ModerationAction> getAuditLogByType(String actionType, int page, int size) {
        return moderationActionRepository.findByActionTypeOrderByCreatedAtDesc(actionType,
                PageRequest.of(page, size));
    }

    /**
     * Get all actions for a specific target (e.g., all actions on a user or song)
     */
    @Transactional(readOnly = true)
    public List<ModerationAction> getActionsForTarget(String targetType, UUID targetId) {
        return moderationActionRepository.findByTarget(targetType, targetId);
    }
}