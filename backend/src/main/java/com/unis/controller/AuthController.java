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

   @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
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
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Login failed: " + e.getMessage());
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

        passwordResetService.requestPasswordReset(email);

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
        emailVerificationService.resend(email);
        return ResponseEntity.ok(Map.of("message", "If an unverified account exists for this email, a new link has been sent."));
    }
}