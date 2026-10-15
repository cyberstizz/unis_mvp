package com.unis.controller;

import com.unis.service.EmailService;
import com.unis.service.digest.WeeklyDigestRenderer;
import com.unis.service.digest.WeeklyDigestService;
import com.unis.service.digest.WeeklyDigestService.Recipient;
import com.unis.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Sunday email, for admins (covered by the /api/v1/admin/** ADMIN rule).
 *
 *   GET  /api/v1/admin/digest/preview            this week's email as you'd get it
 *   GET  /api/v1/admin/digest/preview?asUserId=  as another reader gets it (an artist, say)
 *   POST /api/v1/admin/digest/send-test          sends your own email to your own inbox
 *
 * Built from live standings, exactly like Sunday's run. Neither call touches
 * the weekly run's once-a-day guard.
 */
@RestController
@RequestMapping("/api/v1/admin/digest")
public class AdminDigestController {

    private final WeeklyDigestService digestService;
    private final EmailService emailService;

    public AdminDigestController(WeeklyDigestService digestService, EmailService emailService) {
        this.digestService = digestService;
        this.emailService = emailService;
    }

    @GetMapping("/preview")
    public ResponseEntity<?> preview(@RequestParam(required = false) UUID asUserId) {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        Optional<Recipient> who = digestService.recipient(asUserId != null ? asUserId : me);
        if (who.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No active account with that ID"));
        }
        WeeklyDigestRenderer.Rendered email =
            WeeklyDigestRenderer.render(digestService.build(who.get(), digestService.newRun()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipient", who.get().username());
        body.put("subject", email.subject());
        body.put("preheader", email.preheader());
        body.put("html", email.html());
        body.put("text", email.text());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/send-test")
    public ResponseEntity<?> sendTest() {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        Optional<Recipient> who = digestService.recipient(me);
        if (who.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Your account wasn't found"));
        }
        if (!emailService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Email isn't configured (RESEND_API_KEY)"));
        }
        Recipient r = who.get();
        WeeklyDigestRenderer.Rendered email =
            WeeklyDigestRenderer.render(digestService.build(r, digestService.newRun()));
        boolean ok = emailService.send(r.email(), "[Test] " + email.subject(), email.html(), email.text(),
            Map.of("List-Unsubscribe", "<" + digestService.unsubscribeUrl(r) + ">"), "weekly_digest_test");
        if (!ok) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Resend didn't accept the email. Check the Railway logs for [Email]."));
        }
        return ResponseEntity.ok(Map.of("sentTo", r.email(), "subject", "[Test] " + email.subject()));
    }
}
