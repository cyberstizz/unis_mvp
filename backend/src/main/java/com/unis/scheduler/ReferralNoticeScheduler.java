package com.unis.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.unis.entity.CronExecution;
import com.unis.repository.CronExecutionRepository;
import com.unis.service.EmailService;
import com.unis.service.referralnotice.ReferralNoticeRenderer;
import com.unis.service.referralnotice.ReferralNoticeService;
import com.unis.service.referralnotice.ReferralNoticeService.Pending;
import com.unis.service.referralnotice.ReferralNoticeService.Upline;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ReferralNoticeScheduler -- sends the "new in your network" email.
 *
 * Every 10 minutes it picks up members who joined with a referral code and
 * have confirmed their email, and emails everyone credited for them: the
 * code's owner (level 1, 10%), who invited that person (level 2, 5%), and one
 * step further (level 3, 2%). Who qualifies, and the email itself:
 * ReferralNoticeService and ReferralNoticeRenderer.
 *
 * Safety:
 *   - unis.referral-emails.enabled=false turns it off
 *     (UNIS_REFERRAL_EMAILS_ENABLED in Railway).
 *   - Each signup is announced once: it is claimed before anything is sent.
 *   - daily-limit caps referral emails per UTC day (UNIS_REFERRAL_EMAILS_DAILY_LIMIT,
 *     default 30). On the Sunday email's day, the cap also leaves room under
 *     mail-budget (UNIS_REFERRAL_EMAILS_MAIL_BUDGET, default 95, for Resend's
 *     100-a-day free plan) for password-reset and verification emails. A
 *     signup that doesn't fit waits for tomorrow (up to 3 days after joining).
 *     On a paid Resend plan, raise both.
 *   - If Resend refuses every email for a signup (a bad key, a sender
 *     address it won't accept), the signup goes back in the queue and the
 *     job waits an hour before trying again, so a broken setting shows up as
 *     one FAILED row an hour in the admin cron history instead of a flood.
 *
 * Runs that send something are logged to cron_executions as
 * REFERRAL_NOTICES (awards_created holds the number of emails sent). Runs
 * with nothing to send leave no row.
 */
@Component
public class ReferralNoticeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReferralNoticeScheduler.class);

    static final String JOB_NAME = "REFERRAL_NOTICES";
    static final String TAG = "referral_notice";

    private final ReferralNoticeService noticeService;
    private final EmailService emailService;
    private final CronExecutionRepository cronExecutionRepository;

    private final boolean enabled;
    private final int dailyLimit;
    private final int mailBudget;
    private final int perRun;
    private final long throttleMs;

    public ReferralNoticeScheduler(
            ReferralNoticeService noticeService,
            EmailService emailService,
            CronExecutionRepository cronExecutionRepository,
            @Value("${unis.referral-emails.enabled:true}") boolean enabled,
            @Value("${unis.referral-emails.daily-limit:30}") int dailyLimit,
            @Value("${unis.referral-emails.mail-budget:95}") int mailBudget,
            @Value("${unis.referral-emails.per-run:25}") int perRun,
            @Value("${unis.referral-emails.throttle-ms:600}") long throttleMs) {
        this.noticeService = noticeService;
        this.emailService = emailService;
        this.cronExecutionRepository = cronExecutionRepository;
        this.enabled = enabled;
        this.dailyLimit = dailyLimit;
        this.mailBudget = mailBudget;
        this.perRun = perRun;
        this.throttleMs = throttleMs;
    }

    // Minute 4, 14, 24... so it never lands on the same minute as the other jobs.
    @Scheduled(cron = "${unis.referral-emails.cron:0 4/10 * * * *}", zone = "UTC")
    public void sendReferralNotices() {
        if (!enabled || !emailService.isConfigured()) return;

        // Resend refused everything last time: wait an hour between tries.
        Optional<CronExecution> last = cronExecutionRepository.findTopByJobNameOrderByStartedAtDesc(JOB_NAME);
        if (last.isPresent() && "FAILED".equals(last.get().getStatus())
                && last.get().getStartedAt().isAfter(LocalDateTime.now().minusMinutes(60))) {
            return;
        }

        List<Pending> pending;
        int budget;
        try {
            pending = noticeService.pending(perRun);
            if (pending.isEmpty()) return;
            int sentToday = noticeService.sentToday();
            budget = Math.min(dailyLimit - sentToday,
                              mailBudget - noticeService.digestSentToday() - sentToday);
        } catch (Exception e) {
            log.error("[ReferralNotice] action=run status=error phase=query err={}", e.getMessage());
            return;
        }
        if (budget <= 0) {
            log.info("[ReferralNotice] action=run status=skip reason=daily_limit waiting={}", pending.size());
            return;
        }

        long startNs = System.nanoTime();
        CronExecution exec = null;
        int signups = 0, sent = 0, failed = 0;
        boolean refused = false;

        for (Pending p : pending) {
            List<Upline> readers;
            try {
                readers = noticeService.upline(p.memberId());
            } catch (Exception e) {
                log.error("[ReferralNotice] action=upline status=error member={} err={}", p.memberId(), e.getMessage());
                continue;
            }
            if (readers.size() > budget - sent) {
                log.info("[ReferralNotice] action=run note=daily_limit_reached sentToday+={}", sent);
                break;
            }
            if (!noticeService.claim(p.referralId())) continue; // another run has it
            if (readers.isEmpty()) {                             // nobody to tell (emails off, etc.)
                noticeService.recordSent(p.referralId(), 0);
                continue;
            }
            if (exec == null) exec = cronExecutionRepository.save(new CronExecution(JOB_NAME));

            int ok = 0, bad = 0;
            for (Upline r : readers) {
                try {
                    ReferralNoticeRenderer.Rendered email = ReferralNoticeRenderer.render(noticeService.build(r, p));
                    String unsub = noticeService.unsubscribeUrl(r.unsubscribeToken());
                    boolean accepted = emailService.send(r.email(), email.subject(), email.html(), email.text(),
                        Map.of("List-Unsubscribe", "<" + unsub + ">",
                               "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"), TAG);
                    if (accepted) ok++; else bad++;
                } catch (Exception e) {
                    bad++;
                    log.error("[ReferralNotice] action=build status=error reader={} err={}", r.userId(), e.getMessage());
                }
                sleep();
            }

            if (ok == 0) {
                // Nothing got through: put it back and stop. Most likely Resend
                // itself (key, sender address, quota), so the rest would fail too.
                noticeService.release(p.referralId());
                failed += bad;
                refused = true;
                break;
            }
            noticeService.recordSent(p.referralId(), ok);
            signups++;
            sent += ok;
            failed += bad;
        }

        if (exec == null) return;

        long ms = (System.nanoTime() - startNs) / 1_000_000;
        log.info("[ReferralNotice] action=run status={} signups={} sent={} failed={} durationMs={}", (refused ? "refused" : "ok"), signups, sent, failed, ms);

        if (sent == 0) {
            exec.markFailed("no emails sent; " + failed + " failed. Check the Railway logs for [Email].");
        } else {
            exec.markSuccess(sent);
            if (failed > 0) exec.setErrorMessage(failed + " failed");
        }
        cronExecutionRepository.save(exec);
    }

    private void sleep() {
        if (throttleMs <= 0) return;
        try {
            Thread.sleep(throttleMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
