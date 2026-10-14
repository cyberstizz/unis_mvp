package com.unis.controller;

import com.unis.entity.AccountSuspension;
import com.unis.entity.User;
import com.unis.repository.AdminRoleRepository;
import com.unis.repository.UserRepository;
import com.unis.service.AdminUserService;
import com.unis.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminRoleRepository adminRoleRepository;

    /**
     * GET /api/v1/admin/users?search=&role=&page=0&size=20
     */
    @GetMapping
    public ResponseEntity<Page<User>> getUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adminUserService.getUsers(search, role, page, size));
    }

    /**
     * GET /api/v1/admin/users/{userId}
     * Full user detail with suspension history and admin role info
     */
    @GetMapping("/{userId}")
    public ResponseEntity<?> getUserDetail(@PathVariable UUID userId) {
        User user = userRepository.findByIdWithAssociations(userId)
                .orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("user", user);
        detail.put("isSuspended", adminUserService.isUserSuspended(userId));
        detail.put("suspensionHistory", adminUserService.getSuspensionHistory(userId));
        detail.put("adminRole", adminRoleRepository.findByUserId(userId).orElse(null));
        detail.put("excludeFromMetrics", Boolean.TRUE.equals(user.getExcludeFromMetrics()));
        detail.put("signupSource", user.getSignupSource());
        detail.put("signupPlatform", user.getSignupPlatform());

        return ResponseEntity.ok(detail);
    }

    /**
     * PATCH /api/v1/admin/users/{userId}/metrics-exclusion   {"exclude": true|false, "reason": "..."}
     * Marks a staff or test account so it is left out of every metric from the
     * next nightly run on. Earlier days already computed stay as they were.
     */
    @PatchMapping("/{userId}/metrics-exclusion")
    public ResponseEntity<?> setMetricsExclusion(
            @PathVariable UUID userId,
            @RequestBody Map<String, Object> request) {
        Object raw = request.get("exclude");
        if (!(raw instanceof Boolean)) {
            return ResponseEntity.badRequest().body(Map.of("error", "\"exclude\" must be true or false"));
        }
        boolean exclude = (Boolean) raw;
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        user.setExcludeFromMetrics(exclude);
        userRepository.save(user);

        UUID adminUserId = SecurityUtils.getAuthenticatedUserId();
        String reason = request.get("reason") instanceof String r && !r.isBlank()
                ? r : (exclude ? "Marked as a staff or test account" : "No longer a staff or test account");
        adminUserService.logMetricsExclusion(adminUserId, userId, exclude, reason);

        return ResponseEntity.ok(Map.of("excludeFromMetrics", exclude));
    }

    /**
     * POST /api/v1/admin/users/{userId}/suspend
     */
    @PostMapping("/{userId}/suspend")
    public ResponseEntity<?> suspendUser(
            @PathVariable UUID userId,
            @RequestBody Map<String, Object> request) {
        try {
            UUID adminUserId = SecurityUtils.getAuthenticatedUserId();
            String reason = (String) request.get("reason");
            String suspensionType = (String) request.get("suspensionType");
            String expiresAtStr = (String) request.get("expiresAt");

            if (reason == null || reason.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Reason is required"));
            }
            if (suspensionType == null || suspensionType.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Suspension type is required"));
            }

            LocalDateTime expiresAt = null;
            if (expiresAtStr != null && !expiresAtStr.isBlank()) {
                expiresAt = LocalDateTime.parse(expiresAtStr);
            }

            AccountSuspension suspension = adminUserService.suspendUser(
                    userId, adminUserId, reason, suspensionType, expiresAt);

            return ResponseEntity.ok(Map.of(
                    "message", "User suspended successfully",
                    "suspensionId", suspension.getSuspensionId()));

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/v1/admin/users/{userId}/unsuspend
     */
    @PostMapping("/{userId}/unsuspend")
    public ResponseEntity<?> unsuspendUser(
            @PathVariable UUID userId,
            @RequestBody Map<String, String> request) {
        try {
            UUID adminUserId = SecurityUtils.getAuthenticatedUserId();
            String reason = request.get("reason");

            adminUserService.unsuspendUser(userId, adminUserId,
                    reason != null ? reason : "Suspension lifted by admin");

            return ResponseEntity.ok(Map.of("message", "Suspension lifted successfully"));

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * DELETE /api/v1/admin/users/{userId}
     * Super Admin only (enforced by SecurityConfig)
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<?> deleteUser(@PathVariable UUID userId) {
        try {
            UUID adminUserId = SecurityUtils.getAuthenticatedUserId();
            adminUserService.permanentlyDeleteUser(userId, adminUserId);
            return ResponseEntity.ok(Map.of("message", "User deleted permanently"));

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}