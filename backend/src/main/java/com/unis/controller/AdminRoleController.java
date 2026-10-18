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

    @Autowired
    private com.unis.service.mfa.AdminMfaService adminMfaService;

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

    /**
     * DELETE /api/v1/admin/roles/users/{userId}/mfa
     * Removes a staff member's two-factor setup (lost phone and no recovery
     * codes). They set it up again at their next sign-in. Super admins only
     * (every /admin/roles route is). You can't reset your own this way.
     */
    @DeleteMapping("/users/{userId}/mfa")
    public ResponseEntity<?> resetMfa(@PathVariable UUID userId) {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        if (me.equals(userId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Ask another super admin, or use the reset SQL in V2026_10f__admin_mfa.sql."));
        }
        adminMfaService.reset(userId);
        return ResponseEntity.ok(Map.of("message", "Two-factor sign-in reset. They'll set it up again at their next sign-in."));
    }
}