package com.unis.scheduler;

import com.unis.entity.CronExecution;
import com.unis.repository.CronExecutionRepository;
import com.unis.service.EmailService;
import com.unis.service.ReleaseAlertService;
import com.unis.service.ReleaseAlertService.EmailRecipient;
import com.unis.service.ReleaseAlertService.Item;
import com.unis.service.digest.WeeklyDigestService;
import com.unis.service.email.ReleaseAlertEmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ReleaseAlertEmailScheduler -- the daily "New music from artists you follow"
 * email.
 *
 * Who gets it: followers with email notifications on, release emails on
 * (Settings, or the email's own unsubscribe link), and a confirmed email,
 * when an artist they follow put out something since their last one that
 * they haven't already seen in Unis. Nothing new = no email.
 *
 * When: 22:00 UTC by default -- 5 PM in New York in winter, 6 PM in summer --
 * so a day's music arrives together in the evening. At most one a day.
 *
 * Safety (same as the Sunday email):
 *   - unis.release-alerts.email-enabled=false turns it off
 *     (environment variable UNIS_RELEASEALERTS_EMAILENABLED=false).
 *   - Never runs twice within 20 hours.
 *   - daily-limit caps sends per run (Resend's free plan is 100 a day, shared
 *     with password, verification and Sunday emails). Anyone over the cap
 *     is caught up on the next run, since only a sent email moves their marker.
 *     On a paid Resend plan, raise it with UNIS_RELEASEALERTS_DAILYLIMIT.
 *
 * Logged to cron_executions as RELEASE_ALERTS_EMAIL (awards_created = emails sent).
 */
@Component
public class ReleaseAlertEmailScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReleaseAlertEmailScheduler.class);

    static final String JOB_NAME = "RELEASE_ALERTS_EMAIL";
    static final String TAG = "release_alerts";

    private final ReleaseAlertService alerts;
    private final EmailService emailService;
    private final WeeklyDigestService digestService;   // for absoluteMedia (cover links mail apps can load)
    private final CronExecutionRepository cronExecutionRepository;

    private final boolean enabled;
    private final int dailyLimit;
    private final long throttleMs;
    private final String appBaseUrl;
    private final String apiBaseUrl;
    private final String postalAddress;

    public ReleaseAlertEmailScheduler(
            ReleaseAlertService alerts,
            EmailService emailService,
            WeeklyDigestService digestService,
            CronExecutionRepository cronExecutionRepository,
            @Value("${unis.release-alerts.email-enabled:true}") boolean enabled,
            @Value("${unis.release-alerts.daily-limit:60}") int dailyLimit,
            @Value("${unis.release-alerts.throttle-ms:150}") long throttleMs,
            @Value("${unis.app-base-url:http://localhost:5173}") String appBaseUrl,
            @Value("${unis.api-base-url:http://localhost:8080}") String apiBaseUrl,
            @Value("${unis.mail.postal-address:}") String postalAddress) {
        this.alerts = alerts;
        this.emailService = emailService;
        this.digestService = digestService;
        this.cronExecutionRepository = cronExecutionRepository;
        this.enabled = enabled;
        this.dailyLimit = dailyLimit;
        this.throttleMs = throttleMs;
        this.appBaseUrl = trimSlash(appBaseUrl);
        this.apiBaseUrl = trimSlash(apiBaseUrl);
        this.postalAddress = postalAddress == null ? "" : postalAddress.trim();
    }

    @Scheduled(cron = "${unis.release-alerts.cron:0 0 22 * * *}", zone = "UTC")
    public void sendReleaseAlerts() {
        long startNs = System.nanoTime();
        if (!enabled) {
            log.info("[ReleaseAlertEmail] action=run status=skip reason=disabled");
            return;
        }
        if (!emailService.isConfigured()) {
            log.info("[ReleaseAlertEmail] action=run status=skip reason=email_not_configured");
            return;
        }
        Optional<CronExecution> last = cronExecutionRepository.findTopByJobNameOrderByStartedAtDesc(JOB_NAME);
        if (last.isPresent() && !"FAILED".equals(last.get().getStatus())
                && last.get().getStartedAt().isAfter(LocalDateTime.now().minusHours(20))) {
            log.info("[ReleaseAlertEmail] action=run status=skip reason=already_ran startedAt={}", last.get().getStartedAt());
            return;
        }

        CronExecution exec = cronExecutionRepository.save(new CronExecution(JOB_NAME));
        LocalDateTime now = LocalDateTime.now();

        Map<EmailRecipient, List<Item>> batch;
        try {
            // At most the last two days: someone who just turned emails on
            // doesn't get a backlog, and anyone over yesterday's cap catches up.
            batch = alerts.emailBatch(now.minusHours(48));
        } catch (Exception e) {
            log.error("[ReleaseAlertEmail] action=run status=error phase=query err={}", e.getMessage());
            exec.markFailed("query: " + e.getMessage());
            cronExecutionRepository.save(exec);
            return;
        }

        int total = batch.size(), attempted = 0, sent = 0, failed = 0, skipped = 0;
        for (Map.Entry<EmailRecipient, List<Item>> e : batch.entrySet()) {
            if (attempted >= dailyLimit) {
                skipped = total - attempted;
                log.info("[ReleaseAlertEmail] action=run note=daily_limit_reached limit={} remaining={}", dailyLimit, skipped);
                break;
            }
            attempted++;
            EmailRecipient r = e.getKey();
            try {
                String unsubscribe = apiBaseUrl + "/api/v1/release-alerts/unsubscribe?token=" + r.unsubscribeToken();
                String manage = appBaseUrl + (r.artist() ? "/artistDashboard#settings" : "/profile#settings");
                ReleaseAlertEmail.Rendered email = ReleaseAlertEmail.render(appBaseUrl, digestService::absoluteMedia,
                    e.getValue(), unsubscribe, manage, postalAddress);
                boolean ok = emailService.send(r.email(), email.subject(), email.html(), email.text(),
                    Map.of("List-Unsubscribe", "<" + unsubscribe + ">",
                           "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"), TAG);
                if (ok) {
                    sent++;
                    alerts.markEmailed(r.userId(), now);
                } else {
                    failed++;
                }
            } catch (Exception ex) {
                failed++;
                log.error("[ReleaseAlertEmail] action=send status=error user={} err={}", r.userId(), ex.getMessage());
            }
            if (throttleMs > 0) {
                try {
                    Thread.sleep(throttleMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        long ms = (System.nanoTime() - startNs) / 1_000_000;
        log.info("[ReleaseAlertEmail] action=run status=ok targets={} attempted={} sent={} failed={} skipped={} durationMs={}",
            total, attempted, sent, failed, skipped, ms);
        if (failed > 0 && sent == 0 && attempted > 0) {
            exec.markFailed("no emails sent; " + failed + " failed");
        } else {
            exec.markSuccess(sent);
            if (failed > 0 || skipped > 0) exec.setErrorMessage(failed + " failed, " + skipped + " over the daily limit");
        }
        cronExecutionRepository.save(exec);
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.trim().replaceAll("/+$", "");
    }
}
