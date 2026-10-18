package com.unis.controller;

import com.unis.config.JwtUtil;
import com.unis.dto.AuthResponse;
import com.unis.dto.LoginRequest;
import com.unis.entity.PreRegistration;
import com.unis.repository.PreRegistrationRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.unis.entity.User;
import com.unis.service.EmailVerificationService;
import com.unis.service.PasswordResetService;
import com.unis.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private PreRegistrationRepository preRegistrationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired 
    private EmailVerificationService emailVerificationService;  

    @Autowired
    private com.unis.config.RateLimiter rateLimiter;

    @Autowired
    private com.unis.repository.AdminRoleRepository adminRoleRepository;

    @Autowired
    private com.unis.service.mfa.AdminMfaService adminMfaService;

    /**
     * Pre-launch gate on the server. While true, only accounts with an admin
     * role can sign in, so the "Coming soon" page can't be bypassed by calling
     * the API directly. Railway variable UNIS_PRELAUNCH: "true" until launch
     * day, then "false".
     */
    @org.springframework.beans.factory.annotation.Value("${unis.prelaunch:false}")
    private boolean prelaunch;

    /** Per-email limit, on top of RateLimitFilter's per-IP limit. False = refuse. */
    private boolean allowForEmail(String bucket, String email, int limit, java.time.Duration window) {
        if (email == null) return true;
        String key = bucket + "|email:" + email.trim().toLowerCase();
        return rateLimiter.hit(key, limit, window).allowed();
    }

   @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        // Password guessing against one account from many IPs: 10 tries per
        // 15 minutes per email, whatever the IP.
        if (!allowForEmail("login", loginRequest.getEmail(), 10, java.time.Duration.ofMinutes(15))) {
            String msg = "Too many sign-in attempts for this account. Please wait 15 minutes and try again.";
            return ResponseEntity.status(429).body(Map.of("error", msg, "message", msg));
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginRequest.getEmail(), loginRequest.getPassword())
            );

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            User user = userService.findByEmail(userDetails.getUsername());
            // ★ Hard gate — must confirm email before first login
            if (!Boolean.TRUE.equals(user.getEmailVerified())) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("unverified", true);
                body.put("email", user.getEmail());
                body.put("message", "Please verify your email to activate your account. Check your inbox for the link.");
                return ResponseEntity.status(403).body(body);
            }
            if (prelaunch && adminRoleRepository.findByUserId(user.getUserId()).isEmpty()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("prelaunch", true);
                body.put("message", "Unis isn't open yet. You'll get an email the day it launches.");
                return ResponseEntity.status(403).body(body);
            }
            // Staff accounts (moderator, admin, super admin) need their
            // authenticator code too. The password only earns a short-lived
            // "code needed" pass; /mfa/verify or /mfa/setup/confirm turn it
            // into a real session.
            if (adminMfaService.isStaff(user.getUserId())) {
                if (!adminMfaService.isConfigured()) {
                    String msg = "Admin sign-in is unavailable: UNIS_MFA_KEY isn't set on the server.";
                    return ResponseEntity.status(503).body(Map.of("error", msg, "message", msg));
                }
                boolean setUp = adminMfaService.status(user.getUserId())
                        == com.unis.service.mfa.AdminMfaService.Status.ENABLED;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("mfaRequired", true);
                body.put("setupRequired", !setUp);
                body.put("mfaToken", jwtUtil.generateMfaPendingToken(user.getEmail(), user.getUserId().toString()));
                body.put("message", setUp
                        ? "Enter the 6-digit code from your authenticator app."
                        : "Set up two-factor sign-in to continue.");
                return ResponseEntity.ok(body);
            }

            String token = jwtUtil.generateToken(user.getEmail(), user.getUserId().toString(), user.getRole().toString());

            return ResponseEntity.ok(new AuthResponse(token));
        } catch (BadCredentialsException e) {
            // Normal login failed — check if they're a waitlist user
            try {
                Optional<PreRegistration> waitlistUser = preRegistrationRepository
                    .findByEmail(loginRequest.getEmail().toLowerCase().trim());

                if (waitlistUser.isPresent()) {
                    PreRegistration pr = waitlistUser.get();

                    if (passwordEncoder.matches(loginRequest.getPassword(), pr.getPasswordHash())) {
                        long regionCount = preRegistrationRepository
                            .countByStateCodeAndMetroRegion(pr.getStateCode(), pr.getMetroRegion());

                        int threshold = getThresholdForRegion(pr.getMetroRegion());

                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("waitlist", true);
                        response.put("username", pr.getUsername());
                        response.put("referralCode", pr.getReferralCode());
                        response.put("metroRegion", pr.getMetroRegion());
                        response.put("stateCode", pr.getStateCode());
                        response.put("stateName", pr.getStateName());
                        response.put("regionSignupCount", regionCount);
                        response.put("regionThreshold", threshold);
                        response.put("message", "Your region isn't active yet. Share your referral code to unlock it faster!");

                        return ResponseEntity.status(403).body(response);
                    }
                }
            } catch (Exception ignored) {
                // Waitlist check failed — fall through to normal error
            }

            return ResponseEntity.status(401).body("Invalid email or password");
        } catch (org.springframework.security.core.AuthenticationException e) {
            // Suspended or deleted account, or any other sign-in refusal.
            return ResponseEntity.status(401).body("Invalid email or password");
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AuthController.class).error("[Auth] login error", e);
            return ResponseEntity.status(500).body("Login failed. Please try again.");
        }
    }

    private int getThresholdForRegion(String metroRegion) {
        Set<String> majorMetros = Set.of(
            "Los Angeles", "Chicago", "Atlanta",
            "Houston", "Miami", "Dallas",
            "Phoenix", "Philadelphia", "San Francisco Bay Area",
            "Seattle", "Boston", "Denver",
            "Detroit", "Minneapolis", "Washington DC"
        );
        Set<String> midMarkets = Set.of(
            "Nashville", "Memphis", "New Orleans",
            "Charlotte", "Las Vegas", "Austin",
            "Portland", "San Antonio", "San Diego",
            "Tampa", "Orlando", "Sacramento",
            "Kansas City", "Columbus", "St. Louis",
            "Baltimore", "Milwaukee", "Indianapolis",
            "Cleveland", "Pittsburgh"
        );
        if (majorMetros.contains(metroRegion)) return 1000;
        if (midMarkets.contains(metroRegion)) return 500;
        return 250;
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body("No token provided");
        }

        // NOTE: Later add logic to invalidate the token (e.g., token blacklist)
        return ResponseEntity.ok("Logout successful");
    }

    /**
     * POST /api/auth/forgot-password
     * Always returns 200 — never reveals whether the email exists.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }

        // Max 3 reset emails per address per hour. Still answer 200 either way,
        // so this can't be used to find out which emails have accounts.
        if (allowForEmail("forgot", email, 3, java.time.Duration.ofHours(1))) {
            passwordResetService.requestPasswordReset(email);
        }

        // Always return success — prevents email enumeration
        return ResponseEntity.ok(Map.of(
                "message", "If an account exists with this email, a reset link has been sent."));
    }

    /**
     * POST /api/auth/reset-password
     * Validates token and sets new password.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> request) {
        String token = request.get("token");
        String newPassword = request.get("newPassword");

        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Token is required"));
        }
        if (newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "New password is required"));
        }

        try {
            passwordResetService.resetPassword(token, newPassword);
            return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }


    @PostMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@RequestBody Map<String, String> request) {
        return doVerify(request.get("token"));
    }

    @GetMapping("/verify-email")
    public ResponseEntity<?> verifyEmailGet(@RequestParam("token") String token) {
        return doVerify(token);
    }

    private ResponseEntity<?> doVerify(String token) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Token is required"));
        }
        try {
            emailVerificationService.verify(token);
            return ResponseEntity.ok(Map.of("message", "Email verified. You can now log in."));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Always 200 — never reveals whether the email exists
    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }
        if (allowForEmail("resend-verify", email, 3, java.time.Duration.ofHours(1))) {
            emailVerificationService.resend(email);
        }
        return ResponseEntity.ok(Map.of("message", "If an unverified account exists for this email, a new link has been sent."));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TWO-FACTOR SIGN-IN (staff accounts)
    // Each call takes the "code needed" pass from /login as mfaToken.
    // ═════════════════════════════════════════════════════════════════════════

    /** POST /api/auth/mfa/setup  {mfaToken} → {manualKey, otpauthUri} for the QR code. */
    @PostMapping("/mfa/setup")
    public ResponseEntity<?> mfaSetup(@RequestBody Map<String, String> request) {
        String[] who = mfaPass(request.get("mfaToken"));
        if (who == null) return mfaPassExpired();
        try {
            var setup = adminMfaService.beginSetup(java.util.UUID.fromString(who[1]), who[0]);
            return ResponseEntity.ok(Map.of("manualKey", setup.manualKey(), "otpauthUri", setup.otpauthUri()));
        } catch (com.unis.service.mfa.AdminMfaService.MfaException e) {
            return mfaError(400, e.getMessage());
        }
    }

    /**
     * POST /api/auth/mfa/setup/confirm  {mfaToken, code}
     * → {token, recoveryCodes}: 2FA is on, here's the session and the ten
     * one-time recovery codes (shown only this once).
     */
    @PostMapping("/mfa/setup/confirm")
    public ResponseEntity<?> mfaSetupConfirm(@RequestBody Map<String, String> request) {
        String[] who = mfaPass(request.get("mfaToken"));
        if (who == null) return mfaPassExpired();
        java.util.UUID userId = java.util.UUID.fromString(who[1]);
        try {
            java.util.List<String> codes = adminMfaService.confirmSetup(userId, request.get("code"));
            return ResponseEntity.ok(Map.of("token", fullSession(userId), "recoveryCodes", codes));
        } catch (com.unis.service.mfa.AdminMfaService.MfaException e) {
            return mfaError(400, e.getMessage());
        }
    }

    /** POST /api/auth/mfa/verify  {mfaToken, code} → {token, recoveryCodesLeft}. code = 6 digits or a recovery code. */
    @PostMapping("/mfa/verify")
    public ResponseEntity<?> mfaVerify(@RequestBody Map<String, String> request) {
        String[] who = mfaPass(request.get("mfaToken"));
        if (who == null) return mfaPassExpired();
        java.util.UUID userId = java.util.UUID.fromString(who[1]);
        try {
            if (!adminMfaService.verify(userId, request.get("code"))) {
                return mfaError(401, "That code didn't work. Use the newest code in your app, or a recovery code.");
            }
            return ResponseEntity.ok(Map.of(
                "token", fullSession(userId),
                "recoveryCodesLeft", adminMfaService.recoveryCodesLeft(userId)));
        } catch (com.unis.service.mfa.AdminMfaService.MfaException e) {
            return mfaError(429, e.getMessage());
        }
    }

    /** The {email, userId} inside a valid "code needed" pass, or null. */
    private String[] mfaPass(String mfaToken) {
        if (mfaToken == null || mfaToken.isBlank()) return null;
        try {
            return jwtUtil.parseMfaPendingToken(mfaToken);
        } catch (Exception e) {
            return null;
        }
    }

    /** A real session for a staff account that just passed 2FA. Re-checks the account is still active. */
    private String fullSession(java.util.UUID userId) {
        User user;
        try {
            user = userService.getProfile(userId);
        } catch (RuntimeException e) {
            user = null;
        }
        if (user == null || user.getDeletedAt() != null) {
            throw new com.unis.service.mfa.AdminMfaService.MfaException("This account can't sign in.");
        }
        return jwtUtil.generateToken(user.getEmail(), user.getUserId().toString(), user.getRole().toString(), true);
    }

    private ResponseEntity<?> mfaPassExpired() {
        return mfaError(401, "Your sign-in timed out. Enter your email and password again.");
    }

    private ResponseEntity<?> mfaError(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg, "message", msg));
    }
}