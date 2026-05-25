package com.unis.scheduler;

import com.unis.entity.User;
import com.unis.repository.UserRepository;
import com.unis.service.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DigestEmailScheduler -- sends the daily digest to opted-in users.
 *
 * Targeting is DB-driven (not the provider's scheduler): we query users where
 * email_notifications = true AND deleted_at IS NULL, render each email from
 * our own data, and hand it to EmailService.sendTransactional. The provider
 * (currently Resend) is just a pipe -- moving to SES changes only EmailService.
 *
 * Free-tier guard: Resend's free plan allows ~100 emails/day, 3,000/month,
 * SHARED with password-reset sends. daily-limit defaults to 90 to leave
 * headroom for resets. Once opt-ins approach that, upgrade Resend's paid tier
 * or move to Amazon SES and raise the cap.
 *
 * Cron defaults to 13:00 UTC (~8-9am ET). Requires @EnableScheduling on the
 * application class.
 */
@Component
public class DigestEmailScheduler {

    private final UserRepository userRepository;
    private final EmailService emailService;

    private final boolean enabled;
    private final int dailyLimit;
    private final String appBaseUrl;
    private final String apiBaseUrl;

    public DigestEmailScheduler(
            UserRepository userRepository,
            EmailService emailService,
            @Value("${unis.digest.enabled:true}") boolean enabled,
            @Value("${unis.digest.daily-limit:90}") int dailyLimit,
            @Value("${unis.app-base-url:http://localhost:5173}") String appBaseUrl,
            @Value("${unis.api-base-url:http://localhost:8080}") String apiBaseUrl) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.enabled = enabled;
        this.dailyLimit = dailyLimit;
        this.appBaseUrl = appBaseUrl;
        this.apiBaseUrl = apiBaseUrl;
    }

    @Scheduled(cron = "${unis.digest.cron:0 0 13 * * *}", zone = "UTC")
    public void sendDailyDigest() {
        long startNs = System.nanoTime();

        if (!enabled) {
            System.out.println("[Digest] action=run status=skip reason=disabled");
            return;
        }
        if (!emailService.isConfigured()) {
            System.out.println("[Digest] action=run status=skip reason=email_not_configured");
            return;
        }

        List<User> targets;
        try {
            targets = userRepository.findByEmailNotificationsTrueAndDeletedAtIsNull();
        } catch (Exception e) {
            System.err.println("[Digest] action=run status=error phase=query err=" + e.getMessage());
            return;
        }

        int total = targets.size();
        int attempted = 0, sent = 0, failed = 0, skipped = 0;

        for (User u : targets) {
            if (attempted >= dailyLimit) {
                skipped = total - attempted;
                System.out.println("[Digest] action=run note=daily_limit_reached limit=" + dailyLimit
                    + " remaining=" + skipped);
                break;
            }
            if (u.getEmail() == null || u.getEmail().isBlank()) {
                failed++;
                continue;
            }
            attempted++;
            String html = buildDigestHtml(u);
            boolean ok = emailService.sendTransactional(
                u.getEmail(),
                "Your UNIS daily digest",
                html
            );
            if (ok) sent++; else failed++;
        }

        long ms = (System.nanoTime() - startNs) / 1_000_000;
        System.out.println("[Digest] action=run status=ok totalTargets=" + total
            + " attempted=" + attempted + " sent=" + sent + " failed=" + failed
            + " skipped=" + skipped + " durationMs=" + ms);
    }

    // -------------------------------------------------------------------------
    // Minimal working digest. Enrich with real data (jurisdiction standings,
    // new winners, supported-artist movement) once those queries are defined --
    // that's a product decision, so this ships as a functional skeleton.
    // -------------------------------------------------------------------------
    private String buildDigestHtml(User u) {
        String name = esc(u.getUsername() == null ? "there" : u.getUsername());
        int score = u.getScore() == null ? 0 : u.getScore();
        String unsubscribeUrl = apiBaseUrl + "/api/v1/users/unsubscribe?token=" + u.getUnsubscribeToken();

        return "<div style=\"font-family:Arial,Helvetica,sans-serif;max-width:520px;margin:0 auto;color:#111114;\">"
            + "<h1 style=\"color:#163387;font-size:22px;margin:0 0 4px;\">UNIS</h1>"
            + "<p style=\"font-size:15px;line-height:1.5;\">Hey " + name + ",</p>"
            + "<p style=\"font-size:15px;line-height:1.5;\">Here's where you stand today. Your score is "
            + "<strong>" + score + "</strong>. Cast a vote to move the Harlem leaderboard.</p>"
            + "<p style=\"margin:24px 0;\"><a href=\"" + appBaseUrl + "\" "
            + "style=\"background:#163387;color:#fff;text-decoration:none;padding:12px 20px;"
            + "border-radius:8px;font-size:14px;display:inline-block;\">Open UNIS</a></p>"
            + "<hr style=\"border:none;border-top:1px solid #e4e4e8;margin:24px 0;\"/>"
            + "<p style=\"font-size:12px;color:#888;line-height:1.5;\">You're getting this because email "
            + "notifications are on. <a href=\"" + unsubscribeUrl + "\" style=\"color:#888;\">Unsubscribe</a>."
            + "</p></div>";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}