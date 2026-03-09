package com.unis.service;

import com.unis.entity.AccountSuspension;
import com.unis.entity.AdminRole;
import com.unis.entity.User;
import com.unis.repository.AccountSuspensionRepository;
import com.unis.repository.AdminRoleRepository;
import com.unis.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class AdminUserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminRoleRepository adminRoleRepository;

    @Autowired
    private AccountSuspensionRepository accountSuspensionRepository;

    @Autowired
    private ModerationService moderationService;

    /**
     * Get paginated user list with optional search
     */
    @Transactional(readOnly = true)
    public Page<User> getUsers(String search, String role, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        if (search != null && !search.isBlank()) {
            // Simple search by username or email containing the search term
            // This uses a custom query — we'll need to add this to UserRepository
            return userRepository.searchUsers(search, pageRequest);
        }

        if (role != null && !role.isBlank()) {
            return userRepository.findByRole(User.Role.valueOf(role), pageRequest);
        }

        return userRepository.findAll(pageRequest);
    }

    /**
     * Suspend a user account
     */
    public AccountSuspension suspendUser(UUID userId, UUID adminUserId,
                                          String reason, String suspensionType,
                                          LocalDateTime expiresAt) {
        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        // Check if target is protected
        Optional<AdminRole> targetRole = adminRoleRepository.findByUserId(userId);
        if (targetRole.isPresent() && targetRole.get().getIsProtected()) {
            throw new RuntimeException("This account is protected and cannot be suspended");
        }

        // Check if admin is trying to suspend another admin (only super_admin can)
        if (targetRole.isPresent() && "admin".equals(targetRole.get().getRoleLevel())) {
            AdminRole callerRole = adminRoleRepository.findByUserId(adminUserId)
                    .orElseThrow(() -> new RuntimeException("Caller has no admin role"));
            if (!"super_admin".equals(callerRole.getRoleLevel())) {
                throw new RuntimeException("Only super admins can suspend other admins");
            }
        }

        // Check if already suspended
        if (accountSuspensionRepository.isUserSuspended(userId)) {
            throw new RuntimeException("User is already suspended");
        }

        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new RuntimeException("Admin not found"));

        AccountSuspension suspension = AccountSuspension.builder()
                .user(targetUser)
                .suspendedBy(admin)
                .reason(reason)
                .suspensionType(suspensionType)
                .expiresAt(expiresAt)
                .build();

        AccountSuspension saved = accountSuspensionRepository.save(suspension);

        moderationService.logAction(adminUserId, "account_suspended", "user", userId,
                reason, "Type: " + suspensionType +
                        (expiresAt != null ? ", Expires: " + expiresAt : ", Permanent"));

        return saved;
    }

    /**
     * Lift a suspension
     */
    public void unsuspendUser(UUID userId, UUID adminUserId, String reason) {
        AccountSuspension suspension = accountSuspensionRepository.findActiveSuspension(userId)
                .orElseThrow(() -> new RuntimeException("No active suspension found for user: " + userId));

        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new RuntimeException("Admin not found"));

        suspension.setLiftedAt(LocalDateTime.now());
        suspension.setLiftedBy(admin);
        accountSuspensionRepository.save(suspension);

        moderationService.logAction(adminUserId, "account_unsuspended", "user", userId,
                reason, null);
    }

    /**
     * Permanently delete a user (super admin only — validated at controller level)
     */
    public void permanentlyDeleteUser(UUID userId, UUID adminUserId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        // Check if target is protected
        Optional<AdminRole> targetRole = adminRoleRepository.findByUserId(userId);
        if (targetRole.isPresent() && targetRole.get().getIsProtected()) {
            throw new RuntimeException("This account is protected and cannot be deleted");
        }

        moderationService.logAction(adminUserId, "account_deleted", "user", userId,
                "Permanent deletion by super admin", "Username: " + user.getUsername());

        // Soft delete the user
        user.setDeletedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    /**
     * Get suspension history for a user
     */
    @Transactional(readOnly = true)
    public List<AccountSuspension> getSuspensionHistory(UUID userId) {
        return accountSuspensionRepository.findAllByUserId(userId);
    }

    /**
     * Check if a user is currently suspended
     */
    @Transactional(readOnly = true)
    public boolean isUserSuspended(UUID userId) {
        return accountSuspensionRepository.isUserSuspended(userId);
    }

    /**
     * Get all admin role holders
     */
    @Transactional(readOnly = true)
    public List<AdminRole> getAllAdminRoles() {
        return adminRoleRepository.findAllRoles();
    }

    /**
     * Grant an admin role to a user
     */
    public AdminRole grantRole(UUID userId, String roleLevel, UUID grantedByUserId) {
        // Cannot grant super_admin via API
        if ("super_admin".equals(roleLevel)) {
            throw new RuntimeException("Super admin role can only be assigned directly in the database");
        }

        if (!"admin".equals(roleLevel) && !"moderator".equals(roleLevel)) {
            throw new RuntimeException("Invalid role level: " + roleLevel);
        }

        // Check if user already has a role
        Optional<AdminRole> existing = adminRoleRepository.findByUserId(userId);
        if (existing.isPresent()) {
            throw new RuntimeException("User already has admin role: " + existing.get().getRoleLevel());
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        User grantedBy = userRepository.findById(grantedByUserId)
                .orElseThrow(() -> new RuntimeException("Granting user not found"));

        AdminRole role = AdminRole.builder()
                .user(user)
                .roleLevel(roleLevel)
                .isProtected(false)
                .grantedBy(grantedBy)
                .build();

        AdminRole saved = adminRoleRepository.save(role);

        moderationService.logAction(grantedByUserId, "role_granted", "user", userId,
                "Granted " + roleLevel + " role", null);

        return saved;
    }

    /**
     * Revoke an admin role
     */
    public void revokeRole(UUID adminRoleId, UUID revokedByUserId) {
        AdminRole role = adminRoleRepository.findById(adminRoleId)
                .orElseThrow(() -> new RuntimeException("Admin role not found: " + adminRoleId));

        // Cannot revoke super_admin
        if ("super_admin".equals(role.getRoleLevel())) {
            throw new RuntimeException("Cannot revoke super admin role");
        }

        // Cannot revoke protected accounts
        if (role.getIsProtected()) {
            throw new RuntimeException("Cannot revoke role from protected account");
        }

        UUID userId = role.getUser().getUserId();
        String roleLevel = role.getRoleLevel();

        adminRoleRepository.delete(role);

        moderationService.logAction(revokedByUserId, "role_revoked", "user", userId,
                "Revoked " + roleLevel + " role", null);
    }
}