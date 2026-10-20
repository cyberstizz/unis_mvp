package com.unis.controller;

import com.unis.service.CommunityShareService;
import com.unis.service.ModerationService;
import com.unis.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Admin → Community Share: closing a month and manual balance adjustments.
 *
 *   GET  /api/v1/admin/earnings/closes          every closed month
 *   GET  /api/v1/admin/earnings/preview         what closing a month would do (no writes)
 *   POST /api/v1/admin/earnings/close           close a month: credits everyone's balance
 *   GET  /api/v1/admin/earnings/adjustments     recent manual adjustments
 *   POST /api/v1/admin/earnings/adjustments     add one
 *
 * Reading: admin and super admin (SecurityConfig's /api/v1/admin/** rule).
 * Anything that moves money (POST): super admin only. Every POST is written to
 * the audit log.
 */
@RestController
@RequestMapping("/api/v1/admin/earnings")
public class AdminEarningsController {

    private static final Logger log = LoggerFactory.getLogger(AdminEarningsController.class);

    @Autowired private CommunityShareService communityShareService;
    @Autowired private ModerationService moderationService;

    // GET /api/v1/admin/earnings/closes
    @GetMapping("/closes")
    public ResponseEntity<?> closes() {
        return ResponseEntity.ok(communityShareService.listCloses());
    }

    // GET /api/v1/admin/earnings/preview?month=2026-11&adIncome=120.50&otherIncome=0&feeIncome=4.10
    // feeIncome left out = the suggested amount (Unis's fees on that month's sales and support).
    @GetMapping("/preview")
    public ResponseEntity<?> preview(@RequestParam String month,
                                     @RequestParam(required = false) String adIncome,
                                     @RequestParam(required = false) String otherIncome,
                                     @RequestParam(required = false) String feeIncome) {
        try {
            YearMonth ym = YearMonth.parse(month);
            Map<String, Object> body = new LinkedHashMap<>(communityShareService
                    .plan(ym, amount(adIncome), amount(otherIncome), amount(feeIncome))
                    .summary());
            body.put("suggestedFeeIncome", communityShareService.suggestedFeeIncome(ym));
            body.put("alreadyClosed", communityShareService.isClosed(ym));
            return ResponseEntity.ok(body);
        } catch (DateTimeParseException e) {
            return bad("Month must look like 2026-11.");
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    // POST /api/v1/admin/earnings/close
    // { "month": "2026-11", "adIncome": 120.50, "otherIncome": 0, "feeIncome": 4.10, "note": "AdSense paid Dec 23" }
    @PostMapping("/close")
    public ResponseEntity<?> close(@RequestBody Map<String, Object> body) {
        UUID by = SecurityUtils.getAuthenticatedUserId();
        try {
            YearMonth ym = YearMonth.parse(String.valueOf(body.get("month")));
            String note = body.get("note") == null ? null : String.valueOf(body.get("note"));
            CommunityShareService.Plan plan = communityShareService.close(ym,
                    amount(body.get("adIncome")), amount(body.get("otherIncome")), amount(body.get("feeIncome")),
                    note, by);
            moderationService.logAction(by, "community_share_closed", "earnings", auditTarget(ym.toString()),
                    note == null ? "Closed " + ym : note,
                    "Month: " + ym + " · revenue $" + plan.totalRevenue()
                            + " · to the community $" + plan.paidToCommunity().setScale(2, java.math.RoundingMode.HALF_UP)
                            + " · earners " + plan.earners());
            return ResponseEntity.ok(plan.summary());
        } catch (DateTimeParseException e) {
            return bad("Month must look like 2026-11.");
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "That month is already closed."));
        }
    }

    // GET /api/v1/admin/earnings/adjustments
    @GetMapping("/adjustments")
    public ResponseEntity<?> adjustments(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(communityShareService.listAdjustments(limit));
    }

    // POST /api/v1/admin/earnings/adjustments
    // { "username": "someone", "amount": 50.00, "note": "Live payout test" }  (negative takes money back)
    @PostMapping("/adjustments")
    public ResponseEntity<?> addAdjustment(@RequestBody Map<String, Object> body) {
        UUID by = SecurityUtils.getAuthenticatedUserId();
        try {
            String username = body.get("username") == null ? null : String.valueOf(body.get("username"));
            String note = body.get("note") == null ? null : String.valueOf(body.get("note"));
            Map<String, Object> result = communityShareService.addAdjustment(username, amount(body.get("amount")), note, by);
            moderationService.logAction(by, "earnings_adjusted", "user", (UUID) result.get("userId"),
                    note, "Amount: $" + result.get("amount"));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Accepts 120.5, "120.50" or "$1,204.00"; blank = not given. */
    private static BigDecimal amount(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim().replace("$", "").replace(",", "");
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + raw + "\" isn't an amount. Use numbers like 120.50.");
        }
    }

    /** The audit log needs a target id; each month gets a fixed one. */
    private static UUID auditTarget(String month) {
        return UUID.nameUUIDFromBytes(("unis-community-share:" + month).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map<String, String>> bad(String message) {
        log.info("[CommunityShare] action=admin_request status=refused reason={}", message);
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
