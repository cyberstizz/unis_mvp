package com.unis.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * POST /api/csp-report — where browsers report Content-Security-Policy
 * violations on the website (the "report-uri" in netlify.toml).
 *
 * While the policy is in report-only mode, nothing is blocked; each line
 * here says what WOULD have been blocked, so the policy can be corrected
 * before it's enforced. After it's enforced, a line means something was
 * blocked: usually a browser extension, sometimes a missing host, and
 * rarely an actual injection attempt.
 *
 * Railway log line:
 *   [CSP] directive=img-src blocked=https://example.com page=/song/123
 *
 * Each (directive, blocked host) pair is logged at most once per 10 minutes
 * so a busy page can't flood the logs. Query strings are dropped from URLs
 * (a reset-password link carries its token there). Rate-limited per IP
 * (RateLimitFilter), open to everyone (browsers send these without a sign-in).
 */
@RestController
public class CspReportController {

    private static final Logger log = LoggerFactory.getLogger(CspReportController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BODY_CHARS = 20_000;

    private final Cache<String, Boolean> recentlyLogged = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(5_000)
            .build();

    @PostMapping(value = "/api/csp-report", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Void> report(@RequestBody(required = false) String body) {
        if (body == null || body.isBlank() || body.length() > MAX_BODY_CHARS) {
            return ResponseEntity.noContent().build();
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root.isArray()) {
                // Newer "Reporting API" format: [{ type: "csp-violation", body: {...} }, ...]
                for (JsonNode item : root) {
                    JsonNode b = item.path("body");
                    logOnce(text(b, "effectiveDirective"), text(b, "blockedURL"), text(b, "documentURL"));
                }
            } else {
                // Classic report-uri format: { "csp-report": { ... } }
                JsonNode r = root.path("csp-report");
                String directive = text(r, "effective-directive");
                if (directive.isEmpty()) directive = text(r, "violated-directive");
                logOnce(directive, text(r, "blocked-uri"), text(r, "document-uri"));
            }
        } catch (Exception e) {
            // Not JSON or an unexpected shape: nothing useful to log.
        }
        return ResponseEntity.noContent().build();
    }

    private void logOnce(String directive, String blocked, String page) {
        String d = clean(directive, 60);
        String b = clean(blocked, 200);
        String p = clean(page, 200);
        if (d.isEmpty() && b.isEmpty()) return;
        String key = d + '|' + hostOf(b);
        if (recentlyLogged.getIfPresent(key) != null) return;
        recentlyLogged.put(key, Boolean.TRUE);
        log.warn("[CSP] directive={} blocked={} page={}", d, b, pathOf(p));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("");
    }

    /** Drops query string and fragment, strips line breaks (no fake log lines), caps the length. */
    private static String clean(String value, int max) {
        if (value == null) return "";
        String v = value;
        int cut = indexOfAny(v, '?', '#');
        if (cut >= 0) v = v.substring(0, cut);
        v = v.replaceAll("[\\r\\n\\t]", " ").trim();
        return v.length() > max ? v.substring(0, max) + "…" : v;
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }

    /** "https://cdn.example.com/a/b.png" → "https://cdn.example.com"; "inline" stays "inline". */
    private static String hostOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) return url;
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? url : url.substring(0, slash);
    }

    /** Page address without the site name: "https://unismusic.com/song/1" → "/song/1". */
    private static String pathOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) return url;
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? "/" : url.substring(slash);
    }
}
