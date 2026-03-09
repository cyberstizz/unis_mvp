package com.unis.controller;

import com.unis.entity.AdminRole;
import com.unis.service.AdminUserService;
import com.unis.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/roles")
public class AdminRoleController {

    @Autowired
    private AdminUserService adminUserService;

    /**
     * GET /api/v1/admin/roles
     * List all admin role holders
     */
    @GetMapping
    public ResponseEntity<List<AdminRole>> getAllRoles() {
        return ResponseEntity.ok(adminUserService.getAllAdminRoles());
    }

    /**
     * POST /api/v1/admin/roles
     * Grant admin or moderator role to a user
     */
    @PostMapping
    public ResponseEntity<?> grantRole(@RequestBody Map<String, String> request) {
        try {
            UUID grantedByUserId = SecurityUtils.getAuthenticatedUserId();
            String userIdStr = request.get("userId");
            String roleLevel = request.get("roleLevel");

            if (userIdStr == null || userIdStr.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
            }
            if (roleLevel == null || roleLevel.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "roleLevel is required"));
            }

            UUID userId = UUID.fromString(userIdStr);
            AdminRole role = adminUserService.grantRole(userId, roleLevel, grantedByUserId);

            return ResponseEntity.ok(Map.of(
                    "message", "Role granted successfully",
                    "adminRoleId", role.getAdminRoleId()));

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * DELETE /api/v1/admin/roles/{adminRoleId}
     * Revoke an admin role
     */
    @DeleteMapping("/{adminRoleId}")
    public ResponseEntity<?> revokeRole(@PathVariable UUID adminRoleId) {
        try {
            UUID revokedByUserId = SecurityUtils.getAuthenticatedUserId();
            adminUserService.revokeRole(adminRoleId, revokedByUserId);
            return ResponseEntity.ok(Map.of("message", "Role revoked successfully"));

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}