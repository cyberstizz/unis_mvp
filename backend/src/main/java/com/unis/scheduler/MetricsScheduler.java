package com.unis.scheduler;

import com.unis.entity.CronExecution;
import com.unis.service.CronMonitorService;
import com.unis.service.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the metrics snapshots.
 *
 * Nightly at 1:15 AM New York time: computes yesterday (and any day missed
 * while the server was down, up to 60 days back), then refreshes every open
 * month those days fall in. 1:15 is after the award jobs (12:01) and the
 * territory ranks (12:30), and late "play finished" messages for songs
 * started just before midnight have arrived by then.
 *
 * On the 1st at 2:00 AM: locks the month that just ended. From then on its
 * numbers only change through a super admin's correction, with a reason.
 *
 * Both report to the admin dashboard's job status panel.
 */
@Component
public class MetricsScheduler {

    private static final Logger log = LoggerFactory.getLogger(MetricsScheduler.class);
    static final int MAX_CATCH_UP_DAYS = 60;
    static final int MAX_REBUILD_DAYS = 400;

    private final MetricsService metricsService;
    private final CronMonitorService cronMonitorService;
    private final ExecutorService rebuildExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MetricsScheduler(MetricsService metricsService, CronMonitorService cronMonitorService) {
        this.metricsService = metricsService;
        this.cronMonitorService = cronMonitorService;
    }

    @Scheduled(cron = "0 15 1 * * *", zone = "America/New_York")
    public void nightly() {
        LocalDate yesterday = LocalDate.now(MetricsService.NEW_YORK).minusDays(1);
        LocalDate last = metricsService.lastComputedDay();
        LocalDate from = (last == null || last.isBefore(yesterday.minusDays(MAX_CATCH_UP_DAYS - 1)))
                ? yesterday.minusDays(MAX_CATCH_UP_DAYS - 1)
                : last.plusDays(1);
        if (from.isAfter(yesterday)) {
            from = yesterday;   // already done: recompute yesterday anyway (safe)
        }
        runDays("METRICS_NIGHTLY", from, yesterday);
    }

    @Scheduled(cron = "0 0 2 1 * *", zone = "America/New_York")
    public void monthlyClose() {
        YearMonth ended = YearMonth.now(MetricsService.NEW_YORK).minusMonths(1);
        CronExecution exec = cronMonitorService.startExecution("METRICS_MONTHLY_CLOSE");
        // The nightly run may still be catching up; wait for it (up to an hour).
        if (!acquire(120)) {
            cronMonitorService.markFailed(exec, "Another metrics run was still going after an hour. "
                + "A super admin can lock the month from the Metrics page.");
            return;
        }
        try {
            if (metricsService.isClosed(ended)) {
                cronMonitorService.markSuccess(exec, 0);
                return;
            }
            metricsService.closeMonth(ended, null, null);
            log.info("[Metrics] Locked {}", ended);
            cronMonitorService.markSuccess(exec, 1);
        } catch (Exception e) {
            log.error("[Metrics] Monthly close for {} failed", ended, e);
            cronMonitorService.markFailed(exec, e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /**
     * Super admin "Lock month" (first lock, or a correction with a reason).
     * Runs in the caller's thread; returns false if a metrics run is in progress.
     */
    public boolean closeNow(YearMonth month, java.util.UUID byUserId, String reason) {
        if (!running.compareAndSet(false, true)) return false;
        CronExecution exec = cronMonitorService.startExecution("METRICS_MONTHLY_CLOSE");
        try {
            boolean written = metricsService.closeMonth(month, byUserId, reason);
            cronMonitorService.markSuccess(exec, written ? 1 : 0);
            return written;
        } catch (RuntimeException e) {
            cronMonitorService.markFailed(exec, e.getMessage());
            throw e;
        } finally {
            running.set(false);
        }
    }

    /** Waits for the running flag, checking every 30 seconds. */
    private boolean acquire(int attempts) {
        for (int i = 0; i < attempts; i++) {
            if (running.compareAndSet(false, true)) return true;
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Super admin "Rebuild days": recomputes a range in the background. Locked
     * months are left as they are; reopening one is a separate, logged step.
     *
     * @return false if a run is already in progress or the range is invalid
     */
    public boolean rebuildAsync(LocalDate from, LocalDate to) {
        LocalDate yesterday = LocalDate.now(MetricsService.NEW_YORK).minusDays(1);
        if (to.isAfter(yesterday)) to = yesterday;
        if (from.isAfter(to)) return false;
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > MAX_REBUILD_DAYS) return false;
        if (running.get()) return false;
        final LocalDate a = from;
        final LocalDate b = to;
        rebuildExecutor.submit(() -> runDays("METRICS_REBUILD", a, b));
        return true;
    }

    public boolean isRunning() {
        return running.get();
    }

    private void runDays(String jobName, LocalDate from, LocalDate to) {
        if (!running.compareAndSet(false, true)) {
            log.warn("[Metrics] {} skipped: another metrics run is in progress", jobName);
            return;
        }
        CronExecution exec = cronMonitorService.startExecution(jobName);
        int days = 0;
        try {
            Set<YearMonth> months = new LinkedHashSet<>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                metricsService.computeDay(d);
                months.add(YearMonth.from(d));
                days++;
            }
            for (YearMonth ym : months) {
                if (!metricsService.isClosed(ym)) {
                    metricsService.writeMonth(ym);
                }
            }
            log.info("[Metrics] {} computed {} day(s), {} to {}", jobName, days, from, to);
            cronMonitorService.markSuccess(exec, days);
        } catch (Exception e) {
            log.error("[Metrics] {} failed after {} day(s)", jobName, days, e);
            cronMonitorService.markFailed(exec, e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
