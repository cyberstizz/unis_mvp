package com.unis.controller;

import com.unis.entity.ModerationAction;
import com.unis.service.ModerationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/audit")
public class AdminAuditController {

    @Autowired
    private ModerationService moderationService;

    /**
     * GET /api/v1/admin/audit?page=0&size=20
     * Full audit log, newest first
     */
    @GetMapping
    public ResponseEntity<Page<ModerationAction>> getAuditLog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(moderationService.getAuditLog(page, size));
    }

    /**
     * GET /api/v1/admin/audit/user/{adminId}?page=0&size=20
     * Actions by a specific admin
     */
    @GetMapping("/user/{adminId}")
    public ResponseEntity<Page<ModerationAction>> getAuditLogByAdmin(
            @PathVariable UUID adminId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(moderationService.getAuditLogByAdmin(adminId, page, size));
    }

    /**
     * GET /api/v1/admin/audit/type/{actionType}?page=0&size=20
     * Actions filtered by type
     */
    @GetMapping("/type/{actionType}")
    public ResponseEntity<Page<ModerationAction>> getAuditLogByType(
            @PathVariable String actionType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(moderationService.getAuditLogByType(actionType, page, size));
    }
}