package com.unis.service;

import com.unis.dto.PreRegistrationRequest;
import com.unis.dto.PreRegistrationResponse;
import com.unis.entity.PreRegistration;
import com.unis.entity.User;
import com.unis.repository.PreRegistrationRepository;
import com.unis.service.email.SimpleEmail;
import com.unis.service.email.SimpleEmail.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Visitors: people who join from outside an open area.
 *
 * The waitlist form (/waitlist/join) used to write only a pre_registrations
 * row, and the person couldn't sign in until their city opened. Now it also
 * creates a real account marked visitor:
 *
 *   Can     listen, like, make playlists, follow, comment, message
 *   Can't   vote (VoteController / VoteService) or upload songs, videos or
 *           artist photos (MediaController, ArtistPhotoController)
 *   Charts  their plays and likes still count for the artist (play counts,
 *           points, earnings) but stay out of the race standings
 *           (RankingService, TerritoryRankService)
 *
 * The pre_registrations row stays: it's what counts each city toward its
 * opening number (1,000 / 500 / 250) and what the admin waitlist page reads.
 *
 * THE SWITCH (Railway variables, no code change):
 *   UNIS_VISITORS_MODE       open (default) | list_only
 *   UNIS_VISITORS_DAILY_CAP  most visitors let in per day, Harlem's clock
 *                            (default 200; 0 = no cap)
 * When the switch is list_only or today's cap is reached, a new visitor's
 * account is HELD: their username, code and place in their city's count are
 * kept, but they can't sign in yet. Nobody already in loses access. Every
 * hour, while the switch is open, held visitors are let in oldest first up to
 * what's left of the day's cap, and each one gets a "You're in" email. A held
 * visitor who signs in when there's room is let in on the spot.
 *
 * People who joined the waitlist before visitor accounts existed have only a
 * pre_registrations row. The first time they sign in, convertLegacy() makes
 * their visitor account from it (AuthController).
 */
@Service
public class VisitorService {

    private static final Logger log = LoggerFactory.getLogger(VisitorService.class);

    /** The cap's day follows Harlem's clock, like the voting periods. */
    private static final ZoneId UNIS_ZONE = ZoneId.of("America/New_York");

    /** Most held visitors let in by one hourly run when there's no cap. */
    private static final int MAX_PER_RUN = 500;

    private final PreRegistrationService preRegService;
    private final PreRegistrationRepository preRegRepo;
    private final UserService userService;
    private final EmailService email;
    private final JdbcTemplate jdbc;

    @Value("${unis.visitors.mode:open}")
    private String mode;

    @Value("${unis.visitors.daily-cap:200}")
    private int dailyCap;

    @Value("${unis.app-base-url:${app.frontend.url:https://unismusic.com}}")
    private String appBaseUrl;

    public VisitorService(PreRegistrationService preRegService, PreRegistrationRepository preRegRepo,
                          UserService userService, EmailService email, JdbcTemplate jdbc) {
        this.preRegService = preRegService;
        this.preRegRepo = preRegRepo;
        this.userService = userService;
        this.email = email;
        this.jdbc = jdbc;
    }

    /** A finished sign-up: what the form shows, and the new account (for the verification email). */
    public record Signup(PreRegistrationResponse response, User user) {}

    // ─── Sign-up ────────────────────────────────────────────────────────────

    /**
     * The waitlist form: the city's waitlist entry and the visitor account,
     * together or not at all. Throws IllegalArgumentException with a message
     * the form shows as is.
     *
     * Not one database transaction on purpose: UserService.register mixes JPA
     * saves with plain SQL updates on the new account (the pending referral
     * code), which only see the row once it's saved for real, the same way a
     * Harlem sign-up runs. So the waitlist entry is saved first, and taken back
     * out if the account can't be made.
     */
    public Signup register(PreRegistrationRequest req) {
        if (blank(req.getEmail()) || blank(req.getUsername()) || req.getPassword() == null) {
            throw new IllegalArgumentException("Email, username and password are required.");
        }
        if (req.getPassword().length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }
        if (blank(req.getStateCode()) || blank(req.getStateName()) || blank(req.getMetroRegion())) {
            throw new IllegalArgumentException("Please pick your state and city.");
        }
        if (req.getDateOfBirth() == null) {
            throw new IllegalArgumentException("Please enter your date of birth.");
        }
        if (req.getDateOfBirth().isAfter(LocalDate.now(UNIS_ZONE).minusYears(13))) {
            throw new IllegalArgumentException("You need to be 13 or older to join Unis.");
        }

        if (hasAccount(req.getEmail())) {
            throw new IllegalArgumentException("An account with this email already exists. Sign in instead.");
        }

        PreRegistrationResponse resp = preRegService.register(req);
        boolean held = !admitsNow();

        User account = User.builder()
                .username(req.getUsername().trim())
                .email(req.getEmail().trim())
                .passwordHash(req.getPassword())          // hashed in UserService.register
                .role("ARTIST".equalsIgnoreCase(req.getUserType()) ? User.Role.artist : User.Role.listener)
                .build();
        account.setDateOfBirth(req.getDateOfBirth());
        markVisitor(account, resp.getStateCode(), resp.getStateName(), resp.getMetroRegion(), held);

        User saved;
        try {
            saved = userService.register(account, null, req.getReferredByCode());
        } catch (RuntimeException e) {
            // No account, so no waitlist entry either: the person can fix the
            // form and send it again with the same email and username.
            try {
                preRegRepo.deleteById(resp.getId());
            } catch (Exception cleanup) {
                log.error("[Visitor] action=signup_cleanup status=error preRegId={} err={}",
                        resp.getId(), cleanup.getMessage());
            }
            // UserService's messages ("That username is taken.", ...) are written for people.
            throw new IllegalArgumentException(e.getMessage() == null
                    ? "We couldn't create your account. Please try again." : e.getMessage());
        }

        resp.setVisitorHeld(held);
        log.info("[Visitor] action=signup userId={} region={}, {} held={}",
                saved.getUserId(), resp.getMetroRegion(), resp.getStateCode(), held);
        return new Signup(resp, saved);
    }

    /**
     * Someone who joined the waitlist before visitor accounts existed signs in
     * for the first time: make their visitor account from their waitlist entry.
     * {@code password} is the one they just signed in with (already checked
     * against the entry). Returns null if the account can't be made (for
     * example an old entry with no referral code); they stay on the list.
     */
    public User convertLegacy(PreRegistration entry, String password) {
        if (hasAccount(entry.getEmail())) return null;
        boolean held = !admitsNow();
        User account = User.builder()
                .username(entry.getUsername())
                .email(entry.getEmail())
                .passwordHash(password)                   // hashed in UserService.register
                .role("ARTIST".equalsIgnoreCase(entry.getUserType()) ? User.Role.artist : User.Role.listener)
                .build();
        markVisitor(account, entry.getStateCode(), entry.getStateName(), entry.getMetroRegion(), held);
        try {
            User saved = userService.register(account, null, entry.getReferredBy());
            log.info("[Visitor] action=convert_legacy userId={} held={}", saved.getUserId(), held);
            return saved;
        } catch (RuntimeException e) {
            log.info("[Visitor] action=convert_legacy status=skip reason={}", e.getMessage());
            throw new LegacyConversionFailed();
        }
    }

    /** AuthController falls back to the old waitlist answer. */
    public static class LegacyConversionFailed extends RuntimeException {
        public LegacyConversionFailed() { super("legacy waitlist entry could not become an account"); }
    }

    public boolean hasAccount(String emailAddress) {
        if (blank(emailAddress)) return false;
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE lower(email) = lower(?))", Boolean.class, emailAddress.trim());
        return Boolean.TRUE.equals(exists);
    }

    private static void markVisitor(User u, String stateCode, String stateName, String city, boolean held) {
        u.setVisitor(true);
        u.setVisitorHeld(held);
        u.setVisitorStateCode(stateCode);
        u.setVisitorStateName(stateName);
        u.setVisitorCity(city);
    }

    // ─── The switch and the daily cap ───────────────────────────────────────

    public boolean isOpen() {
        return mode == null || "open".equalsIgnoreCase(mode.trim());
    }

    /** Can one more visitor come in right now? */
    public boolean admitsNow() {
        if (!isOpen()) return false;
        return dailyCap <= 0 || admittedToday() < dailyCap;
    }

    /** Visitors let in today (Harlem's day): joined and not held, or released today. */
    public long admittedToday() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE visitor = TRUE AND visitor_held = FALSE "
                        + "AND COALESCE(visitor_released_at, created_at) >= ?",
                Long.class, Timestamp.valueOf(startOfToday()));
        return n == null ? 0 : n;
    }

    private static LocalDateTime startOfToday() {
        // users.created_at is written in the server's local time, so compare in it.
        return LocalDate.now(UNIS_ZONE).atStartOfDay(UNIS_ZONE)
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    /** A held visitor signing in: let them in if there's room. True if they're in now. */
    public boolean tryRelease(User u) {
        if (!Boolean.TRUE.equals(u.getVisitorHeld())) return true;
        if (!admitsNow()) return false;
        int n = jdbc.update(
                "UPDATE users SET visitor_held = FALSE, visitor_released_at = ? WHERE user_id = ? AND visitor_held = TRUE",
                Timestamp.valueOf(LocalDateTime.now()), u.getUserId());
        u.setVisitorHeld(false);
        if (n == 1) log.info("[Visitor] action=release userId={} via=sign_in", u.getUserId());
        return true;
    }

    /** What sign-in answers a held visitor. Same shape as the old waitlist answer, plus held. */
    public Map<String, Object> heldBody(User u) {
        String state = u.getVisitorStateCode();
        String city = u.getVisitorCity();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("waitlist", true);
        body.put("held", true);
        body.put("username", u.getUsername());
        body.put("referralCode", u.getReferralCode());
        body.put("metroRegion", city);
        body.put("stateCode", state);
        body.put("stateName", u.getVisitorStateName());
        body.put("regionSignupCount", state == null || city == null ? 0 : preRegRepo.countByStateCodeAndMetroRegion(state, city));
        body.put("regionThreshold", PreRegistrationService.thresholdForRegion(city));
        body.put("message", "We're letting out-of-town visitors in a few at a time right now. "
                + "Your username and your spot are held, and we'll email you the moment you're in.");
        return body;
    }

    /** "Dallas, TX", for messages. */
    public static String label(User u) {
        String city = u.getVisitorCity();
        String state = u.getVisitorStateCode();
        if (blank(city)) return blank(state) ? "your city" : state;
        return blank(state) ? city : city + ", " + state;
    }

    public static boolean isVisitor(User u) {
        return u != null && Boolean.TRUE.equals(u.getVisitor());
    }

    /** The reason a visitor's vote is refused (VoteController, VoteService). */
    public static String voteRefusal(User u) {
        String city = blank(u.getVisitorCity()) ? "your city" : u.getVisitorCity();
        return "Voting is for people who live in an open area. You're visiting from " + label(u)
                + ", so your vote opens the day " + city + " does.";
    }

    /** The reason a visitor's upload is refused (MediaController, ArtistPhotoController). */
    public static String uploadRefusal(User u) {
        String city = blank(u.getVisitorCity()) ? "your city" : u.getVisitorCity();
        return "Uploads open when " + city + " does. Your artist name is held until then.";
    }

    // ─── Hourly: let held visitors in ───────────────────────────────────────

    @Scheduled(cron = "0 20 * * * *", zone = "UTC")
    public void letInHeldVisitors() {
        try {
            if (!isOpen()) return;
            long room = dailyCap <= 0 ? MAX_PER_RUN : Math.min(MAX_PER_RUN, dailyCap - admittedToday());
            if (room <= 0) return;

            List<Map<String, Object>> held = jdbc.queryForList(
                    "SELECT user_id, email, username, visitor_city FROM users "
                            + "WHERE visitor = TRUE AND visitor_held = TRUE AND deleted_at IS NULL "
                            + "ORDER BY created_at LIMIT ?", room);
            int letIn = 0;
            int emailed = 0;
            for (Map<String, Object> row : held) {
                UUID id = (UUID) row.get("user_id");
                // Only the run that flips the flag sends the email, so a second
                // server instance can't send it twice.
                int n = jdbc.update(
                        "UPDATE users SET visitor_held = FALSE, visitor_released_at = ? WHERE user_id = ? AND visitor_held = TRUE",
                        Timestamp.valueOf(LocalDateTime.now()), id);
                if (n != 1) continue;
                letIn++;
                if (sendYoureIn((String) row.get("email"), (String) row.get("visitor_city"))) emailed++;
            }
            if (letIn > 0) log.info("[Visitor] action=release_batch let_in={} emailed={}", letIn, emailed);
        } catch (Exception e) {
            log.error("[Visitor] action=release_batch status=error err={}", e.getMessage());
        }
    }

    private boolean sendYoureIn(String to, String city) {
        if (!email.isConfigured() || blank(to)) return false;
        String place = blank(city) ? "your city" : city;
        SimpleEmail.Rendered mail = SimpleEmail.render(
                "You're in on Unis",
                "You're in, " + place + ".",
                List.of("Your visitor account is ready. Listen, like, make playlists and message anyone on Unis.",
                        "Voting opens the day " + place + " does. Every sign-up with your code brings that day closer."),
                List.of(new Button("Sign in to Unis", appBaseUrl + "/login", true)),
                "You're getting this because you joined Unis from " + place + ".");
        return email.send(to, mail.subject(), mail.html(), mail.text(), null, "visitor_access");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
