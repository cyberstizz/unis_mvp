package com.unis.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Limits how fast anyone can hit the endpoints that cost money, send email or
 * texts, or can be used to guess passwords. Over the limit: HTTP 429 with a
 * Retry-After header and a short JSON message.
 *
 * Runs after JwtRequestFilter (wired in SecurityConfig), so "per account"
 * rules can key on the signed-in user and fall back to the IP when nobody is
 * signed in.
 *
 * The client IP is the rightmost X-Forwarded-For entry: Railway's edge
 * appends the real address there, and anything to its left was sent by the
 * caller and can be faked.
 *
 * Every rule a request matches is checked, so a login is held to both the
 * login rule and the general per-IP ceiling.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private enum Key { IP, ACCOUNT }

    private record Rule(String name, String method, String pattern, int limit, Duration window, Key key) {}

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration QUARTER = Duration.ofMinutes(15);
    private static final Duration HOUR = Duration.ofHours(1);

    private static final List<Rule> RULES = List.of(
        // ── Sign-in and account recovery (password guessing, email flooding) ──
        new Rule("login",          "POST", "/api/auth/login",                       10, MINUTE,  Key.IP),
        new Rule("login-hour",     "POST", "/api/auth/login",                       60, HOUR,    Key.IP),
        new Rule("forgot",         "POST", "/api/auth/forgot-password",              5, HOUR,    Key.IP),
        new Rule("forgot-v1",      "POST", "/api/v1/auth/forgot-password",           5, HOUR,    Key.IP),
        new Rule("reset",          "POST", "/api/auth/reset-password",              10, HOUR,    Key.IP),
        new Rule("reset-v1",       "POST", "/api/v1/auth/reset-password",           10, HOUR,    Key.IP),
        new Rule("resend-verify",  "POST", "/api/auth/resend-verification",          5, HOUR,    Key.IP),
        new Rule("verify-email",   null,   "/api/auth/verify-email",                30, HOUR,    Key.IP),
        new Rule("two-factor",     "POST", "/api/auth/mfa/**",                      20, QUARTER, Key.IP),

        // ── Sign-up (fake accounts, free file hosting) ──
        new Rule("register",       "POST", "/api/v1/users/register",                10, HOUR,    Key.IP),
        new Rule("signup-photo",   "PATCH","/api/v1/users/profile/photo",           20, HOUR,    Key.IP),
        new Rule("signup-song",    "POST", "/api/v1/media/signup-song",             10, HOUR,    Key.IP),
        new Rule("waitlist",       "POST", "/api/v1/waitlist/register",             10, HOUR,    Key.IP),
        new Rule("availability",   "GET",  "/api/v1/users/check-*",                 60, MINUTE,  Key.IP),
        new Rule("referral-check", "GET",  "/api/v1/users/validate-referral/**",    60, MINUTE,  Key.IP),

        // ── Text messages cost money per send ──
        new Rule("sms-start",      "POST", "/api/v1/phone/start",                    5, HOUR,    Key.ACCOUNT),
        new Rule("sms-check",      "POST", "/api/v1/phone/check",                   10, QUARTER, Key.ACCOUNT),

        // ── Things that move the charts or reach other people ──
        new Rule("vote",           "POST", "/api/v1/vote/submit",                   30, MINUTE,  Key.ACCOUNT),
        new Rule("play",           "POST", "/api/v1/media/song/*/play",             60, MINUTE,  Key.ACCOUNT),
        new Rule("anon-play",      "POST", "/api/v1/media/song/*/anonymous-play",   60, MINUTE,  Key.IP),
        new Rule("video-play",     "POST", "/api/v1/media/video/*/play",            60, MINUTE,  Key.ACCOUNT),
        new Rule("like",           "POST", "/api/v1/media/*/*/like",                60, MINUTE,  Key.ACCOUNT),
        new Rule("comment",        "POST", "/api/v1/comments",                      20, MINUTE,  Key.ACCOUNT),
        new Rule("message",        "POST", "/api/v1/messages/**",                   30, MINUTE,  Key.ACCOUNT),
        new Rule("upload",         "POST", "/api/v1/media/song",                    20, HOUR,    Key.ACCOUNT),
        new Rule("upload-video",   "POST", "/api/v1/media/video",                   20, HOUR,    Key.ACCOUNT),
        new Rule("payment",        "POST", "/api/v1/support/*/intent",              20, HOUR,    Key.ACCOUNT),
        new Rule("purchase",       "POST", "/api/v1/songs/*/purchase",              20, HOUR,    Key.ACCOUNT),
        new Rule("payout",         "POST", "/api/v1/stripe/payout",                  5, HOUR,    Key.ACCOUNT),

        // ── Public forms ──
        new Rule("dmca",           "POST", "/api/v1/dmca/submit",                   10, HOUR,    Key.IP),
        new Rule("activity",       "POST", "/api/v1/activity/track",               120, MINUTE,  Key.IP),
        new Rule("csp-report",     "POST", "/api/csp-report",                       30, MINUTE,  Key.IP),

        // ── General ceiling for everything else (a busy shared Wi-Fi stays well under this) ──
        new Rule("api",            null,   "/api/**",                              600, MINUTE,  Key.IP)
    );

    private final RateLimiter limiter;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String ip = clientIp(request);
        String account = null;

        for (Rule rule : RULES) {
            if (rule.method() != null && !rule.method().equalsIgnoreCase(request.getMethod())) continue;
            if (!matcher.match(rule.pattern(), path)) continue;

            String who;
            if (rule.key() == Key.ACCOUNT) {
                if (account == null) account = signedInUser();
                who = account != null ? "u:" + account : "ip:" + ip;
            } else {
                who = "ip:" + ip;
            }

            RateLimiter.Result result = limiter.hit(rule.name() + '|' + who, rule.limit(), rule.window());
            if (!result.allowed()) {
                log.warn("[RateLimit] rule={} key={} path={} retryAfter={}s", rule.name(), who, path,
                    result.retryAfterSeconds());
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
                response.setContentType("application/json");
                String msg = "Too many requests. Please wait " + result.retryAfterSeconds() + " seconds and try again.";
                // "error" and "message": the apps read one or the other.
                response.getWriter().write("{\"error\":\"" + msg + "\",\"message\":\"" + msg
                    + "\",\"retryAfterSeconds\":" + result.retryAfterSeconds() + "}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Rightmost X-Forwarded-For entry (set by Railway's edge), else the socket address. */
    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty()) return last;
        }
        return request.getRemoteAddr();
    }

    private static String signedInUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getCredentials() == null) return null;
        return auth.getCredentials().toString();
    }
}
