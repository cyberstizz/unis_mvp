package com.unis.controller;

import com.unis.scheduler.MetricsScheduler;
import com.unis.service.MetricsReportService;
import com.unis.service.MetricsService;
import com.unis.service.ModerationService;
import com.unis.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The admin Metrics pages and PDF reports read from here.
 *
 * Reading: admin and super admin (SecurityConfig's /api/v1/admin/** rule).
 * Rebuilding days and locking or correcting a month: super admin only.
 */
@RestController
@RequestMapping("/api/v1/admin/metrics")
public class AdminMetricsController {

    private static final int MAX_RANGE_DAYS = 366;

    @Autowired private MetricsReportService reportService;
    @Autowired private MetricsService metricsService;
    @Autowired private MetricsScheduler metricsScheduler;
    @Autowired private ModerationService moderationService;

    // GET /api/v1/admin/metrics/status
    @GetMapping("/status")
    public ResponseEntity<?> status() {
        Map<String, Object> s = reportService.status();
        s.put("running", metricsScheduler.isRunning());
        return ResponseEntity.ok(s);
    }

    // GET /api/v1/admin/metrics/definitions
    @GetMapping("/definitions")
    public ResponseEntity<?> definitions() {
        return ResponseEntity.ok(reportService.definitions());
    }

    // GET /api/v1/admin/metrics/month?month=2026-11
    @GetMapping("/month")
    public ResponseEntity<?> month(@RequestParam String month) {
        try {
            return ResponseEntity.ok(reportService.month(YearMonth.parse(month)));
        } catch (DateTimeParseException e) {
            return bad("Month must look like 2026-11.");
        }
    }

    // GET /api/v1/admin/metrics/range?from=2026-11-21&to=2026-12-05
    @GetMapping("/range")
    public ResponseEntity<?> range(@RequestParam String from, @RequestParam String to) {
        try {
            LocalDate a = LocalDate.parse(from);
            LocalDate b = LocalDate.parse(to);
            if (b.isBefore(a)) return bad("The end date is before the start date.");
            if (java.time.temporal.ChronoUnit.DAYS.between(a, b) + 1 > MAX_RANGE_DAYS) {
                return bad("A custom range can cover at most " + MAX_RANGE_DAYS + " days.");
            }
            return ResponseEntity.ok(reportService.range(a, b));
        } catch (DateTimeParseException e) {
            return bad("Dates must look like 2026-11-21.");
        }
    }

    // GET /api/v1/admin/metrics/daily?from=&to=&keys=dau,mau&segmentType=all
    @GetMapping("/daily")
    public ResponseEntity<?> daily(@RequestParam String from, @RequestParam String to,
                                   @RequestParam String keys,
                                   @RequestParam(defaultValue = "all") String segmentType) {
        try {
            List<String> keyList = Arrays.stream(keys.split(",")).map(String::trim)
                .filter(k -> !k.isEmpty()).toList();
            if (keyList.isEmpty()) return bad("Name at least one metric.");
            return ResponseEntity.ok(reportService.daily(LocalDate.parse(from), LocalDate.parse(to), keyList, segmentType));
        } catch (DateTimeParseException e) {
            return bad("Dates must look like 2026-11-21.");
        }
    }

    // GET /api/v1/admin/metrics/cohorts?segmentType=all&segmentValue=all&includePreview=false
    @GetMapping("/cohorts")
    public ResponseEntity<?> cohorts(@RequestParam(defaultValue = "all") String segmentType,
                                     @RequestParam(defaultValue = "all") String segmentValue,
                                     @RequestParam(defaultValue = "false") boolean includePreview) {
        return ResponseEntity.ok(reportService.cohorts(segmentType, segmentValue, includePreview));
    }

    // POST /api/v1/admin/metrics/rebuild  {"from": "2026-11-21", "to": "2026-11-30"}   (super admin)
    @PostMapping("/rebuild")
    public ResponseEntity<?> rebuild(@RequestBody Map<String, String> body) {
        try {
            LocalDate from = LocalDate.parse(body.get("from"));
            LocalDate to = LocalDate.parse(body.get("to"));
            boolean started = metricsScheduler.rebuildAsync(from, to);
            if (!started) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                    "Couldn't start. Another metrics run may be going, or the range is wrong "
                  + "(up to 400 days, ending no later than yesterday)."));
            }
            UUID by = SecurityUtils.getAuthenticatedUserId();
            moderationService.logAction(by, "metrics_rebuilt", "metrics", auditTarget("rebuild"),
                "Rebuilt days " + from + " to " + to, null);
            return ResponseEntity.accepted().body(Map.of("message",
                "Rebuilding " + from + " to " + to + ". Locked months are not changed. "
              + "Progress shows in the job status panel."));
        } catch (DateTimeParseException | NullPointerException e) {
            return bad("Send from and to as dates like 2026-11-21.");
        }
    }

    // POST /api/v1/admin/metrics/close  {"month": "2026-11", "reason": "..."}   (super admin)
    // Locks a finished month, or recomputes a locked one (a correction) with a reason.
    @PostMapping("/close")
    public ResponseEntity<?> close(@RequestBody Map<String, String> body) {
        YearMonth month;
        try {
            month = YearMonth.parse(String.valueOf(body.get("month")));
        } catch (DateTimeParseException e) {
            return bad("Month must look like 2026-11.");
        }
        if (!month.isBefore(YearMonth.now(MetricsService.NEW_YORK))) {
            return bad("A month can only be locked after it has ended.");
        }
        String reason = body.get("reason");
        boolean locked = metricsService.isClosed(month);
        if (locked && (reason == null || reason.isBlank())) {
            return bad("This month is already locked. Changing it needs a written reason.");
        }
        UUID by = SecurityUtils.getAuthenticatedUserId();
        boolean written = metricsScheduler.closeNow(month, by, reason);
        if (!written) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                "Another metrics run is going. Try again in a few minutes."));
        }
        moderationService.logAction(by, locked ? "metrics_month_corrected" : "metrics_month_locked",
            "metrics", auditTarget(month.toString()), locked ? reason : "Locked " + month, "Month: " + month);
        return ResponseEntity.ok(Map.of("message", locked
            ? "Recomputed " + month + ". The reason will appear on its reports."
            : "Locked " + month + "."));
    }

    /** The audit log needs a target id; metrics actions use a fixed id per month (or "rebuild"). */
    private static UUID auditTarget(String name) {
        return UUID.nameUUIDFromBytes(("unis-metrics:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map<String, String>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
