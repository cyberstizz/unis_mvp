package com.unis.scheduler;

import com.unis.entity.CronExecution;
import com.unis.repository.CronExecutionRepository;
import com.unis.service.EmailService;
import com.unis.service.digest.WeeklyDigest;
import com.unis.service.digest.WeeklyDigestRenderer;
import com.unis.service.digest.WeeklyDigestService;
import com.unis.service.digest.WeeklyDigestService.Recipient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DigestEmailScheduler -- sends the Sunday email ("Sunday on Unis").
 *
 * Who gets it: everyone with email notifications on, a confirmed email, and
 * an active account. Each reader's email is built from the live standings
 * (WeeklyDigestService) and drawn by WeeklyDigestRenderer; Resend is just
 * the pipe (EmailService.send).
 *
 * When: Sunday 13:00 UTC by default -- 9 AM in New York in summer, 8 AM in
 * winter -- so the email lands on the last day of the week, while votes can
 * still change who wins.
 *
 * Safety:
 *   - unis.digest.enabled=false turns it off (UNIS_DIGEST_ENABLED in Railway).
 *   - Never sends twice in a day: if a run already started in the last 20
 *     hours (a redeploy at 13:00, a second instance), this run skips.
 *   - daily-limit caps sends per run. Resend's free plan allows 100 emails a
 *     day, shared with password-reset and verification emails, so the default
 *     of 90 leaves room for those. On a paid Resend plan, raise it
 *     (UNIS_DIGEST_DAILY_LIMIT).
 *   - throttle-ms between sends keeps well under Resend's rate limit.
 *
 * Each run is logged to cron_executions as WEEKLY_DIGEST (awards_created holds
 * the number of emails sent), so it shows in the admin cron history.
 */
@Component
public class DigestEmailScheduler {

    static final String JOB_NAME = "WEEKLY_DIGEST";
    static final String TAG = "weekly_digest";

    private final WeeklyDigestService digestService;
    private final EmailService emailService;
    private final CronExecutionRepository cronExecutionRepository;

    private final boolean enabled;
    private final int dailyLimit;
    private final long throttleMs;

    public DigestEmailScheduler(
            WeeklyDigestService digestService,
            EmailService emailService,
            CronExecutionRepository cronExecutionRepository,
            @Value("${unis.digest.enabled:true}") boolean enabled,
            @Value("${unis.digest.daily-limit:90}") int dailyLimit,
            @Value("${unis.digest.throttle-ms:150}") long throttleMs) {
        this.digestService = digestService;
        this.emailService = emailService;
        this.cronExecutionRepository = cronExecutionRepository;
        this.enabled = enabled;
        this.dailyLimit = dailyLimit;
        this.throttleMs = throttleMs;
    }

    @Scheduled(cron = "${unis.digest.cron:0 0 13 * * SUN}", zone = "UTC")
    public void sendWeeklyDigest() {
        long startNs = System.nanoTime();

        if (!enabled) {
            System.out.println("[Digest] action=run status=skip reason=disabled");
            return;
        }
        if (!emailService.isConfigured()) {
            System.out.println("[Digest] action=run status=skip reason=email_not_configured");
            return;
        }
        Optional<CronExecution> last = cronExecutionRepository.findTopByJobNameOrderByStartedAtDesc(JOB_NAME);
        if (last.isPresent() && !"FAILED".equals(last.get().getStatus())
                && last.get().getStartedAt().isAfter(LocalDateTime.now().minusHours(20))) {
            System.out.println("[Digest] action=run status=skip reason=already_ran startedAt=" + last.get().getStartedAt());
            return;
        }

        CronExecution exec = cronExecutionRepository.save(new CronExecution(JOB_NAME));

        List<Recipient> targets;
        try {
            targets = digestService.recipients();
        } catch (Exception e) {
            System.err.println("[Digest] action=run status=error phase=query err=" + e.getMessage());
            exec.markFailed("recipient query: " + e.getMessage());
            cronExecutionRepository.save(exec);
            return;
        }

        WeeklyDigestService.Run run = digestService.newRun();
        int total = targets.size();
        int attempted = 0, sent = 0, failed = 0, skipped = 0;

        for (Recipient r : targets) {
            if (attempted >= dailyLimit) {
                skipped = total - attempted;
                System.out.println("[Digest] action=run note=daily_limit_reached limit=" + dailyLimit
                    + " remaining=" + skipped);
                break;
            }
            attempted++;
            try {
                WeeklyDigest digest = digestService.build(r, run);
                WeeklyDigestRenderer.Rendered email = WeeklyDigestRenderer.render(digest);
                boolean ok = emailService.send(r.email(), email.subject(), email.html(), email.text(),
                    Map.of("List-Unsubscribe", "<" + digestService.unsubscribeUrl(r) + ">",
                           // RFC 8058: lets Gmail/Yahoo/Apple Mail show their own
                           // "Unsubscribe" button, which POSTs to the link above.
                           "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"), TAG);
                if (ok) sent++; else failed++;
            } catch (Exception e) {
                failed++;
                System.err.println("[Digest] action=build status=error user=" + r.userId() + " err=" + e.getMessage());
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
        System.out.println("[Digest] action=run status=ok totalTargets=" + total
            + " attempted=" + attempted + " sent=" + sent + " failed=" + failed
            + " skipped=" + skipped + " durationMs=" + ms);

        if (failed > 0 && sent == 0 && attempted > 0) {
            exec.markFailed("no emails sent; " + failed + " failed");
        } else {
            exec.markSuccess(sent);
            if (failed > 0 || skipped > 0) {
                exec.setErrorMessage(failed + " failed, " + skipped + " over the daily limit");
            }
        }
        cronExecutionRepository.save(exec);
    }
}
