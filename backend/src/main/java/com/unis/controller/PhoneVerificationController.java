package com.unis.controller;

import com.unis.entity.User;
import com.unis.repository.UserRepository;
import com.unis.service.TwilioVerifyService;
import com.unis.util.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.unis.service.UserService;

import java.util.Map;
import java.util.UUID;

/**
 * Phone verification via Twilio Verify.
 *
 * Flow:
 *   POST /api/v1/phone/start  { phoneNumber } -> texts a 6-digit code
 *   POST /api/v1/phone/check  { code }        -> flips phone_verified on success
 *   GET  /api/v1/phone/status                 -> { phoneVerified, phone(masked) }
 *
 * All three are authenticated (the default rule in SecurityConfig covers
 * /api/v1/phone/**), so the userId always comes from the JWT, never the body.
 */
@RestController
@RequestMapping("/api/v1/phone")
public class PhoneVerificationController {

    private final TwilioVerifyService twilio;
    private final UserRepository userRepository;
    private final UserService userService;


    public PhoneVerificationController(TwilioVerifyService twilio, UserRepository userRepository, UserService userService) {
        this.twilio = twilio;
        this.userRepository = userRepository;
        this.userService = userService;

    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, String> body) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        if (Boolean.TRUE.equals(user.getPhoneVerified())) {
            return ResponseEntity.ok(Map.of("alreadyVerified", true));
        }

        String phone = normalize(body.get("phoneNumber"));
        if (phone == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Enter a valid phone number."));
        }

        try {
            user.setPhoneNumber(phone);
            userRepository.save(user);
            twilio.startVerification(phone);
            return ResponseEntity.ok(Map.of("sent", true, "phone", mask(phone)));
        } catch (Exception e) {
            // Trial accounts reject numbers that aren't verified caller IDs — surface a clean message.
            return ResponseEntity.status(502).body(Map.of(
                "error", "Could not send a code to that number. Double-check it and try again."));
        }
    }

    @PostMapping("/check")
    public ResponseEntity<?> check(@RequestBody Map<String, String> body) {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        if (Boolean.TRUE.equals(user.getPhoneVerified())) {
            return ResponseEntity.ok(Map.of("verified", true));
        }

        String phone = user.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request a code first."));
        }
        String code = body.getOrDefault("code", "").trim();
        if (code.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Enter the code we texted you."));
        }

        try {
            boolean approved = twilio.checkVerification(phone, code);
            if (!approved) {
                return ResponseEntity.status(400).body(Map.of("error", "That code didn't match. Try again."));
            }
        userService.markPhoneVerified(userId);
        return ResponseEntity.ok(Map.of("verified", true));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "That code is invalid or has expired."));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        UUID userId = SecurityUtils.getAuthenticatedUserId();
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        return ResponseEntity.ok(Map.of(
            "phoneVerified", Boolean.TRUE.equals(user.getPhoneVerified()),
            "phone", user.getPhoneNumber() == null ? "" : mask(user.getPhoneNumber())
        ));
    }

    // --- helpers ---------------------------------------------------------------

    /** Best-effort E.164 normalisation. Defaults bare 10-digit input to US (+1). */
    private static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[^0-9+]", "");
        if (s.isEmpty()) return null;
        if (s.startsWith("+")) return s.length() >= 11 ? s : null;
        if (s.length() == 10) return "+1" + s;
        if (s.length() == 11 && s.startsWith("1")) return "+" + s;
        return null;
    }

    private static String mask(String e164) {
        if (e164 == null || e164.length() < 4) return "\u2022\u2022\u2022";
        return "\u2022\u2022\u2022\u2022 \u2022\u2022\u2022 " + e164.substring(e164.length() - 4);
    }
}