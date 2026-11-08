package com.unis.controller;

import com.unis.service.EmailService;
import com.unis.service.referralnotice.ReferralNotice;
import com.unis.service.referralnotice.ReferralNoticeRenderer;
import com.unis.service.referralnotice.ReferralNoticeService;
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
 * The "new in your network" email, for admins (covered by the
 * /api/v1/admin/** ADMIN rule).
 *
 *   GET  /api/v1/admin/referral-notice/preview?level=1     the email you'd get
 *   POST /api/v1/admin/referral-notice/send-test?level=1   sends it to your own inbox
 *
 * Built from your own account and network. The new member is your most recent
 * referral, or a stand-in if you have none. Neither call touches the queue.
 */
@RestController
@RequestMapping("/api/v1/admin/referral-notice")
public class AdminReferralNoticeController {

    private final ReferralNoticeService noticeService;
    private final EmailService emailService;

    public AdminReferralNoticeController(ReferralNoticeService noticeService, EmailService emailService) {
        this.noticeService = noticeService;
        this.emailService = emailService;
    }

    @GetMapping("/preview")
    public ResponseEntity<?> preview(@RequestParam(defaultValue = "1") int level) {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        Optional<ReferralNotice> notice = noticeService.sample(me, level);
        if (notice.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Your account wasn't found"));
        }
        ReferralNoticeRenderer.Rendered email = ReferralNoticeRenderer.render(notice.get());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipient", notice.get().recipientName());
        body.put("subject", email.subject());
        body.put("preheader", email.preheader());
        body.put("html", email.html());
        body.put("text", email.text());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/send-test")
    public ResponseEntity<?> sendTest(@RequestParam(defaultValue = "1") int level) {
        UUID me = SecurityUtils.getAuthenticatedUserId();
        if (!emailService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Email isn't configured (RESEND_API_KEY)"));
        }
        Optional<String> to = noticeService.emailOf(me);
        Optional<ReferralNotice> notice = noticeService.sample(me, level);
        if (to.isEmpty() || notice.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Your account wasn't found"));
        }
        ReferralNoticeRenderer.Rendered email = ReferralNoticeRenderer.render(notice.get());
        String subject = "[Test] " + email.subject();
        boolean ok = emailService.send(to.get(), subject, email.html(), email.text(),
            Map.of("List-Unsubscribe", "<" + notice.get().unsubscribeUrl() + ">"), "referral_notice_test");
        if (!ok) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Resend didn't accept the email. Check the Railway logs for [Email]."));
        }
        return ResponseEntity.ok(Map.of("sentTo", to.get(), "subject", subject));
    }
}
