package com.unis.controller;

import com.unis.service.ReleaseAlertService;
import com.unis.util.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Release alerts (see ReleaseAlertService).
 *
 *   GET  /api/v1/release-alerts                 the New releases list (90 days)
 *   GET  /api/v1/release-alerts/summary         unseen count + the newest three, for the Messages row and badges
 *   POST /api/v1/release-alerts/seen            mark everything seen
 *   GET  /api/v1/release-alerts/settings        { inApp, email }
 *   PUT  /api/v1/release-alerts/settings        { inApp?, email? }
 *   GET  /api/v1/release-alerts/follows         followed artists with their bell
 *   GET  /api/v1/release-alerts/follow/{id}     { following, level }
 *   PUT  /api/v1/release-alerts/follow/{id}     { level: ALL | MAJOR | OFF }
 *   GET  /api/v1/release-alerts/reach           the artist's latest alert (dashboard; managers too)
 *   GET  /api/v1/release-alerts/unsubscribe     page with a button (public; the token is the key)
 *   POST /api/v1/release-alerts/unsubscribe     turns release emails off (public; RFC 8058 one-click)
 */
@RestController
@RequestMapping("/api/v1/release-alerts")
public class ReleaseAlertController {

    private final ReleaseAlertService alerts;

    public ReleaseAlertController(ReleaseAlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public ResponseEntity<List<ReleaseAlertService.Item>> list() {
        return ResponseEntity.ok(alerts.list(SecurityUtils.getAuthenticatedUserId()));
    }

    @GetMapping("/summary")
    public ResponseEntity<ReleaseAlertService.Summary> summary() {
        return ResponseEntity.ok(alerts.summary(SecurityUtils.getAuthenticatedUserId()));
    }

    @PostMapping("/seen")
    public ResponseEntity<Void> seen() {
        alerts.markSeen(SecurityUtils.getAuthenticatedUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/settings")
    public ResponseEntity<ReleaseAlertService.Settings> settings() {
        return ResponseEntity.ok(alerts.settings(SecurityUtils.getAuthenticatedUserId()));
    }

    @PutMapping("/settings")
    public ResponseEntity<ReleaseAlertService.Settings> updateSettings(@RequestBody Map<String, Boolean> body) {
        return ResponseEntity.ok(alerts.updateSettings(SecurityUtils.getAuthenticatedUserId(),
            body.get("inApp"), body.get("email")));
    }

    @GetMapping("/follows")
    public ResponseEntity<List<ReleaseAlertService.FollowedArtist>> follows() {
        return ResponseEntity.ok(alerts.followed(SecurityUtils.getAuthenticatedUserId()));
    }

    @GetMapping("/follow/{artistId}")
    public ResponseEntity<Map<String, Object>> level(@PathVariable UUID artistId) {
        String level = alerts.level(SecurityUtils.getAuthenticatedUserId(), artistId);
        Map<String, Object> out = new HashMap<>();
        out.put("following", level != null);
        out.put("level", level);
        return ResponseEntity.ok(out);
    }

    @PutMapping("/follow/{artistId}")
    public ResponseEntity<Map<String, Object>> setLevel(@PathVariable UUID artistId, @RequestBody Map<String, String> body) {
        String level = alerts.setLevel(SecurityUtils.getAuthenticatedUserId(), artistId, body.get("level"));
        return ResponseEntity.ok(Map.of("following", true, "level", level));
    }

    @GetMapping("/reach")
    public ResponseEntity<ReleaseAlertService.Reach> reach() {
        ReleaseAlertService.Reach r = alerts.latestForArtist(SecurityUtils.getAuthenticatedUserId());
        return r == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(r);
    }

    // ── Email unsubscribe (release emails only) ─────────────────────────────
    // GET shows a button and changes nothing (mail scanners open every link);
    // POST unsubscribes. Both permitAll in SecurityConfig.

    @GetMapping("/unsubscribe")
    public ResponseEntity<String> unsubscribePage(@RequestParam("token") UUID token) {
        return page("Stop release emails?",
            "You'll stop getting emails when artists you follow drop new music. You'll still see it in Unis, and you can turn the emails back on in Settings.",
            "<form method=\"post\"><input type=\"hidden\" name=\"List-Unsubscribe\" value=\"One-Click\">"
                + "<button type=\"submit\">Stop release emails</button></form>");
    }

    @PostMapping("/unsubscribe")
    public ResponseEntity<String> unsubscribe(@RequestParam("token") UUID token) {
        boolean ok = alerts.unsubscribeByToken(token);
        return ok
            ? page("Release emails are off",
                "You won't get emails about new music anymore. Changed your mind? Turn them back on in Settings.", "")
            : page("This link doesn't work",
                "This unsubscribe link is invalid. You can turn release emails off in Settings instead.", "");
    }

    private static ResponseEntity<String> page(String title, String text, String action) {
        String body = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<meta name=\"robots\" content=\"noindex\">"
            + "<title>Unis email settings</title>"
            + "<style>"
            + "body{margin:0;background:#0b0d12;color:#f4f6fb;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;}"
            + "main{max-width:440px;margin:0 auto;padding:64px 24px;text-align:center;}"
            + ".mark{font-weight:800;letter-spacing:.18em;font-size:14px;color:#6b8aff;margin:0 0 28px;}"
            + "h1{font-size:22px;margin:0 0 12px;}"
            + "p{color:#9aa3b2;font-size:15px;line-height:1.5;margin:0 0 28px;}"
            + "button{font:inherit;font-weight:700;font-size:15px;color:#fff;background:#2952cc;border:0;border-radius:10px;padding:13px 28px;cursor:pointer;}"
            + "</style></head><body><main>"
            + "<p class=\"mark\">UNIS</p>"
            + "<h1>" + title + "</h1>"
            + "<p>" + text + "</p>"
            + action
            + "</main></body></html>";
        return ResponseEntity.ok()
            .header("Content-Type", "text/html; charset=UTF-8")
            .header("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
            .header("Cache-Control", "no-store")
            .header("Referrer-Policy", "no-referrer")
            .body(body);
    }
}
