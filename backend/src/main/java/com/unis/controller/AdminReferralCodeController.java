package com.unis.controller;

import com.unis.service.ReferralCodeService;
import com.unis.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin → Codes. Short referral codes for campaigns and partners, and
 * sign-ups per code. Admin and super admin (the /api/v1/admin/** rule).
 *
 *   GET   /api/v1/admin/referral-codes            short codes with owner and sign-up counts
 *   POST  /api/v1/admin/referral-codes            { code, ownerUsername, label } → creates one
 *   PATCH /api/v1/admin/referral-codes/{code}     { active } → switches it on or off
 *   GET   /api/v1/admin/referral-codes/report     sign-ups per code (every code, not just short ones)
 */
@RestController
@RequestMapping("/api/v1/admin/referral-codes")
public class AdminReferralCodeController {

    private static final Logger log = LoggerFactory.getLogger(AdminReferralCodeController.class);

    private final ReferralCodeService codes;

    public AdminReferralCodeController(ReferralCodeService codes) {
        this.codes = codes;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(Map.of("codes", codes.list()));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        try {
            Map<String, Object> created = codes.create(body.get("code"), body.get("ownerUsername"), body.get("label"),
                    SecurityUtils.getAuthenticatedUserId());
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[ReferralCodes] action=create status=error err={}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Could not create the code."));
        }
    }

    @PatchMapping("/{code}")
    public ResponseEntity<?> setActive(@PathVariable String code, @RequestBody Map<String, Object> body) {
        Object active = body.get("active");
        if (!(active instanceof Boolean on)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Send { active: true } or { active: false }."));
        }
        try {
            codes.setActive(code, on, SecurityUtils.getAuthenticatedUserId());
            return ResponseEntity.ok(Map.of("code", ReferralCodeService.normalize(code), "active", on));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/report")
    public ResponseEntity<?> report() {
        return ResponseEntity.ok(Map.of("rows", codes.report()));
    }
}
