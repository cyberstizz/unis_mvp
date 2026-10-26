package com.unis.service;

import com.unis.service.email.SimpleEmail;
import com.unis.service.email.SimpleEmail.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The Unis street team: members who meet up to sign people up in person, or
 * share Unis with people they already know.
 *
 * It's a members' program, not a job. Nobody is assigned shifts, quotas or
 * hours; anyone can skip any meetup; the only earnings are the referral
 * program every member already has. 18 and older only.
 *
 *   1. /team/join: a Unis member fills in the form with their account's email.
 *      We email a confirmation link (status pending until they tap it). No
 *      login is needed anywhere, so it works before launch.
 *   2. Each member has a private token. Every email link carries it:
 *      /team/me?t=TOKEN opens their page (meetups, their code, I'm in /
 *      Can't make it). The token is a random UUID, never shown to anyone else.
 *   3. An admin posts a meetup (Admin → Street team) and invites members.
 *      Members answer from the email; a reminder goes out the day before.
 *   4. After the meetup the admin marks who came, and sees the sign-ups made
 *      with going members' codes during it.
 *
 * Tables: street_team_members, team_meetups, team_rsvps
 * (backend/sql/V2026_10k__launch_marketing.sql).
 */
@Service
public class StreetTeamService {

    private static final Logger log = LoggerFactory.getLogger(StreetTeamService.class);

    public static final ZoneId HARLEM = ZoneId.of("America/New_York");
    public static final int MIN_AGE = 18;

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^[0-9+()\\-.\\s]{7,24}$");
    private static final Set<String> ROLES = Set.of("in_person", "network");
    private static final Set<String> SLOTS = Set.of("weekday_day", "weekday_evening", "weekend_day", "weekend_evening");
    private static final Set<String> SIDES = Set.of("uptown", "downtown", "either");
    private static final Set<String> ANSWERS = Set.of("going", "not_going");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private final JdbcTemplate jdbc;
    private final EmailService email;

    @Value("${app.frontend.url:https://unismusic.com}")
    private String frontendUrl;

    /** Optional: the code /team/join offers people with no account yet (e.g. a "TEAM" short code). */
    @Value("${STREET_TEAM_SIGNUP_CODE:}")
    private String signupCode;

    public StreetTeamService(JdbcTemplate jdbc, EmailService email) {
        this.jdbc = jdbc;
        this.email = email;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Public: join, confirm, member page, replies
    // ═════════════════════════════════════════════════════════════════════════

    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        String code = ReferralCodeService.normalize(signupCode);
        m.put("signupCode", code.isEmpty() ? null : code);
        m.put("minAge", MIN_AGE);
        return m;
    }

    /**
     * The join form. Always ends the same way for the caller ("check your
     * email"), whether or not the email has an account, so the form can't be
     * used to find out who's on Unis. What the email says depends on the case.
     */
    @Transactional
    public void join(String rawEmail, String rawName, String rawPhone, List<String> roles, List<String> availability,
                     String side, boolean adult, boolean acceptedRules) {
        String mail = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
        if (!EMAIL.matcher(mail).matches()) throw new IllegalArgumentException("Enter the email on your Unis account.");
        String name = rawName == null ? "" : rawName.trim();
        if (name.length() < 2 || name.length() > 80) throw new IllegalArgumentException("Enter your name.");
        String phone = rawPhone == null ? "" : rawPhone.trim();
        if (!phone.isEmpty() && !PHONE.matcher(phone).matches()) throw new IllegalArgumentException("That phone number doesn't look right.");
        String roleList = cleanList(roles, ROLES);
        if (roleList.isEmpty()) throw new IllegalArgumentException("Pick at least one way to help.");
        String slotList = cleanList(availability, SLOTS);
        String where = side != null && SIDES.contains(side) ? side : "either";
        if (!adult) throw new IllegalArgumentException("The street team is for people " + MIN_AGE + " and older.");
        if (!acceptedRules) throw new IllegalArgumentException("Please read and accept the street team rules.");

        List<Map<String, Object>> users = jdbc.query(
                "SELECT user_id, date_of_birth FROM users WHERE LOWER(email) = ? AND deleted_at IS NULL",
                (rs, i) -> {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("userId", rs.getObject("user_id", UUID.class));
                    u.put("dob", rs.getObject("date_of_birth", LocalDate.class));
                    return u;
                }, mail);

        if (users.isEmpty()) {
            sendNoAccount(mail, name);
            log.info("[StreetTeam] action=join status=no_account");
            return;
        }

        UUID userId = (UUID) users.get(0).get("userId");
        LocalDate dob = (LocalDate) users.get(0).get("dob");
        if (dob != null && Period.between(dob, LocalDate.now(HARLEM)).getYears() < MIN_AGE) {
            sendUnderAge(mail, name);
            log.info("[StreetTeam] action=join status=under_age user={}", userId);
            return;
        }

        List<Map<String, Object>> existing = jdbc.query(
                "SELECT status, token, updated_at FROM street_team_members WHERE user_id = ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("status", rs.getString("status"));
                    m.put("token", rs.getObject("token", UUID.class));
                    m.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
                    return m;
                }, userId);

        if (!existing.isEmpty() && "active".equals(existing.get(0).get("status"))) {
            // Already on the team. Details aren't changed from a public form;
            // they get a link to their own page instead.
            sendAlreadyMember(mail, name, (UUID) existing.get(0).get("token"));
            log.info("[StreetTeam] action=join status=already_member user={}", userId);
            return;
        }

        UUID token;
        if (existing.isEmpty()) {
            token = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO street_team_members (user_id, full_name, roles, availability, side, phone, status, token) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)",
                    userId, name, roleList, slotList, where, phone.isEmpty() ? null : phone, token);
        } else {
            token = (UUID) existing.get(0).get("token");
            Instant last = (Instant) existing.get(0).get("updatedAt");
            boolean recent = "pending".equals(existing.get(0).get("status"))
                    && last.isAfter(Instant.now().minusSeconds(5 * 60));
            jdbc.update(
                    "UPDATE street_team_members SET full_name = ?, roles = ?, availability = ?, side = ?, phone = ?, " +
                    "status = CASE WHEN confirmed_at IS NULL THEN 'pending' ELSE 'active' END, updated_at = NOW() " +
                    "WHERE user_id = ?",
                    name, roleList, slotList, where, phone.isEmpty() ? null : phone, userId);
            if (recent) {
                log.info("[StreetTeam] action=join status=resend_skipped reason=recent user={}", userId);
                return;
            }
        }
        sendConfirm(mail, name, token);
        log.info("[StreetTeam] action=join status=pending user={}", userId);
    }

    /** The confirmation link. Turns a pending member active; harmless to repeat. */
    @Transactional
    public Optional<Map<String, Object>> confirm(UUID token) {
        int n = jdbc.update(
                "UPDATE street_team_members SET status = 'active', confirmed_at = COALESCE(confirmed_at, NOW()), updated_at = NOW() " +
                "WHERE token = ? AND status = 'pending'", token);
        if (n > 0) log.info("[StreetTeam] action=confirm status=ok");
        return member(token);
    }

    /** A member's own page: who they are, their code, and upcoming meetups with their answers. */
    public Optional<Map<String, Object>> member(UUID token) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT m.user_id, m.full_name, m.roles, m.availability, m.side, m.status, u.username, u.referral_code " +
                "FROM street_team_members m JOIN users u ON u.user_id = m.user_id " +
                "WHERE m.token = ? AND u.deleted_at IS NULL",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", rs.getObject("user_id", UUID.class));
                    m.put("name", rs.getString("full_name"));
                    m.put("username", rs.getString("username"));
                    m.put("referralCode", rs.getString("referral_code"));
                    m.put("roles", split(rs.getString("roles")));
                    m.put("availability", split(rs.getString("availability")));
                    m.put("side", rs.getString("side"));
                    m.put("status", rs.getString("status"));
                    return m;
                }, token);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> me = rows.get(0);
        UUID userId = (UUID) me.remove("userId");
        me.put("referrals", jdbc.queryForObject(
                "SELECT COUNT(*) FROM referrals r JOIN users n ON n.user_id = r.referred_id AND n.deleted_at IS NULL " +
                "WHERE r.referrer_id = ?", Long.class, userId));
        me.put("meetups", jdbc.query(
                "SELECT t.*, r.answer AS my_answer, " +
                "  (SELECT COUNT(*) FROM team_rsvps x WHERE x.meetup_id = t.meetup_id AND x.answer = 'going') AS going " +
                "FROM team_meetups t LEFT JOIN team_rsvps r ON r.meetup_id = t.meetup_id AND r.user_id = ? " +
                "WHERE t.ends_at >= NOW() ORDER BY t.starts_at LIMIT 20",
                (rs, i) -> meetupRow(rs, true), userId));
        return Optional.of(me);
    }

    /** I'm in / Can't make it. */
    @Transactional
    public Map<String, Object> reply(UUID token, UUID meetupId, String answer) {
        if (!ANSWERS.contains(answer)) throw new IllegalArgumentException("Answer going or not_going.");
        List<Map<String, Object>> who = jdbc.query(
                "SELECT m.user_id, m.status FROM street_team_members m JOIN users u ON u.user_id = m.user_id " +
                "WHERE m.token = ? AND u.deleted_at IS NULL",
                (rs, i) -> Map.<String, Object>of("userId", rs.getObject("user_id", UUID.class), "status", rs.getString("status")),
                token);
        if (who.isEmpty()) throw new IllegalArgumentException("This link doesn't work anymore. Open your newest street team email.");
        if ("pending".equals(who.get(0).get("status"))) throw new IllegalArgumentException("Confirm your email first.");
        UUID userId = (UUID) who.get(0).get("userId");

        List<Map<String, Object>> meetup = jdbc.query(
                "SELECT status, ends_at, spots, " +
                "  (SELECT COUNT(*) FROM team_rsvps x WHERE x.meetup_id = t.meetup_id AND x.answer = 'going' AND x.user_id <> ?) AS others " +
                "FROM team_meetups t WHERE meetup_id = ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("status", rs.getString("status"));
                    m.put("endsAt", rs.getTimestamp("ends_at").toInstant());
                    m.put("spots", (Integer) rs.getObject("spots"));
                    m.put("others", rs.getLong("others"));
                    return m;
                }, userId, meetupId);
        if (meetup.isEmpty()) throw new IllegalArgumentException("That meetup isn't on the list anymore.");
        Map<String, Object> mt = meetup.get(0);
        if ("cancelled".equals(mt.get("status"))) throw new IllegalArgumentException("That meetup was cancelled.");
        if (((Instant) mt.get("endsAt")).isBefore(Instant.now())) throw new IllegalArgumentException("That meetup is over.");
        Integer spots = (Integer) mt.get("spots");
        if ("going".equals(answer) && spots != null && (Long) mt.get("others") >= spots) {
            throw new IllegalArgumentException("That one is full. Thanks for offering; try another meetup.");
        }

        jdbc.update(
                "INSERT INTO team_rsvps (meetup_id, user_id, answer) VALUES (?, ?, ?) " +
                "ON CONFLICT (meetup_id, user_id) DO UPDATE SET answer = EXCLUDED.answer, responded_at = NOW()",
                meetupId, userId, answer);
        log.info("[StreetTeam] action=reply meetup={} user={} answer={}", meetupId, userId, answer);
        return member(token).orElseThrow();
    }

    /** Emails on (active) or off (paused). Only for confirmed members. */
    @Transactional
    public Map<String, Object> setEmails(UUID token, boolean on) {
        int n = jdbc.update(
                "UPDATE street_team_members SET status = ?, updated_at = NOW() WHERE token = ? AND confirmed_at IS NOT NULL",
                on ? "active" : "paused", token);
        if (n == 0) throw new IllegalArgumentException("Confirm your email first.");
        return member(token).orElseThrow();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Admin
    // ═════════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> members() {
        return jdbc.query(
                "SELECT m.user_id, m.full_name, m.roles, m.availability, m.side, m.phone, m.status, m.joined_at, " +
                "  m.confirmed_at, u.username, u.email, u.referral_code, " +
                "  (SELECT COUNT(*) FROM referrals r JOIN users n ON n.user_id = r.referred_id AND n.deleted_at IS NULL " +
                "     WHERE r.referrer_id = m.user_id) AS referrals, " +
                "  (SELECT COUNT(*) FROM team_rsvps x WHERE x.user_id = m.user_id AND x.attended = TRUE) AS came " +
                "FROM street_team_members m JOIN users u ON u.user_id = m.user_id " +
                "WHERE u.deleted_at IS NULL ORDER BY m.joined_at DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", rs.getObject("user_id", UUID.class));
                    m.put("name", rs.getString("full_name"));
                    m.put("username", rs.getString("username"));
                    m.put("email", rs.getString("email"));
                    m.put("phone", rs.getString("phone"));
                    m.put("referralCode", rs.getString("referral_code"));
                    m.put("roles", split(rs.getString("roles")));
                    m.put("availability", split(rs.getString("availability")));
                    m.put("side", rs.getString("side"));
                    m.put("status", rs.getString("status"));
                    m.put("joinedAt", iso(rs.getTimestamp("joined_at")));
                    m.put("confirmedAt", iso(rs.getTimestamp("confirmed_at")));
                    m.put("referrals", rs.getLong("referrals"));
                    m.put("meetupsCame", rs.getLong("came"));
                    return m;
                });
    }

    @Transactional
    public void setMemberStatus(UUID userId, String status) {
        if (!Set.of("active", "paused").contains(status)) throw new IllegalArgumentException("Status must be active or paused.");
        int n = jdbc.update(
                "UPDATE street_team_members SET status = ?, updated_at = NOW() WHERE user_id = ? AND confirmed_at IS NOT NULL",
                status, userId);
        if (n == 0) throw new IllegalArgumentException("That member hasn't confirmed their email yet.");
    }

    public List<Map<String, Object>> meetups() {
        return jdbc.query(
                "SELECT t.*, " +
                "  (SELECT COUNT(*) FROM team_rsvps x WHERE x.meetup_id = t.meetup_id AND x.answer = 'going') AS going, " +
                "  (SELECT COUNT(*) FROM team_rsvps x WHERE x.meetup_id = t.meetup_id AND x.answer = 'not_going') AS not_going, " +
                "  (SELECT COUNT(*) FROM team_rsvps x WHERE x.meetup_id = t.meetup_id AND x.attended = TRUE) AS came " +
                "FROM team_meetups t " +
                "ORDER BY (t.ends_at < NOW()), CASE WHEN t.ends_at >= NOW() THEN t.starts_at END ASC, t.starts_at DESC " +
                "LIMIT 200",
                (rs, i) -> {
                    Map<String, Object> m = meetupRow(rs, false);
                    m.put("notGoing", rs.getLong("not_going"));
                    m.put("came", rs.getLong("came"));
                    m.put("invitedAt", iso(rs.getTimestamp("invited_at")));
                    return m;
                });
    }

    /**
     * Creates a meetup. Times come from the admin's datetime inputs
     * ("2026-11-07T12:00") and are read as New York time.
     */
    @Transactional
    public Map<String, Object> createMeetup(Map<String, Object> body, UUID by) {
        Fields f = fields(body);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO team_meetups (meetup_id, title, meeting_point, starts_at, ends_at, side, spots, notes, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, f.title, f.meetingPoint, Timestamp.from(f.startsAt), Timestamp.from(f.endsAt), f.side, f.spots, f.notes, by);
        log.info("[StreetTeam] action=create_meetup meetup={} by={}", id, by);
        return meetup(id).orElseThrow();
    }

    /** Edits a meetup, or cancels it ({ status: "cancelled" }), which emails everyone going. */
    @Transactional
    public Map<String, Object> updateMeetup(UUID id, Map<String, Object> body, UUID by) {
        Map<String, Object> current = meetup(id).orElseThrow(() -> new IllegalArgumentException("No such meetup."));
        if ("cancelled".equals(body.get("status"))) {
            if (!"cancelled".equals(current.get("status"))) {
                jdbc.update("UPDATE team_meetups SET status = 'cancelled' WHERE meetup_id = ?", id);
                int told = 0;
                for (Recipient r : recipients(id, "going")) {
                    SimpleEmail.Rendered mail = SimpleEmail.render(
                            current.get("title") + " is cancelled",
                            current.get("title") + " is cancelled",
                            List.of("Hi " + firstName(r.name) + ", the meetup on " + when(current) + " is off. Sorry for the change.",
                                    "There are other meetups on your street team page."),
                            List.of(new Button("See other meetups", memberLink(r.token), true)),
                            footer(r.token));
                    if (email.send(r.email, mail.subject(), mail.html(), mail.text(), null, "street_team")) told++;
                }
                log.info("[StreetTeam] action=cancel_meetup meetup={} by={} told={}", id, by, told);
            }
            return meetup(id).orElseThrow();
        }
        Fields f = fields(body);
        jdbc.update(
                "UPDATE team_meetups SET title = ?, meeting_point = ?, starts_at = ?, ends_at = ?, side = ?, spots = ?, notes = ?, " +
                "reminded_at = CASE WHEN starts_at <> ? THEN NULL ELSE reminded_at END WHERE meetup_id = ?",
                f.title, f.meetingPoint, Timestamp.from(f.startsAt), Timestamp.from(f.endsAt), f.side, f.spots, f.notes,
                Timestamp.from(f.startsAt), id);
        log.info("[StreetTeam] action=edit_meetup meetup={} by={}", id, by);
        return meetup(id).orElseThrow();
    }

    /**
     * Emails the meetup to active members who haven't answered it yet.
     *   scope "match": members whose side and usual times fit (a blank
     *                  availability counts as "any time");
     *   scope "all":   every active member.
     */
    public Map<String, Object> invite(UUID id, String scope, UUID by) {
        Map<String, Object> mt = meetup(id).orElseThrow(() -> new IllegalArgumentException("No such meetup."));
        if ("cancelled".equals(mt.get("status"))) throw new IllegalArgumentException("That meetup is cancelled.");
        Instant starts = Instant.parse((String) mt.get("startsAt"));
        if (Instant.parse((String) mt.get("endsAt")).isBefore(Instant.now())) throw new IllegalArgumentException("That meetup is over.");
        String slot = slotOf(starts);
        String side = (String) mt.get("side");
        boolean all = "all".equals(scope);

        List<Map<String, Object>> members = jdbc.query(
                "SELECT m.user_id, m.full_name, m.availability, m.side, m.token, u.email " +
                "FROM street_team_members m JOIN users u ON u.user_id = m.user_id " +
                "WHERE m.status = 'active' AND u.deleted_at IS NULL " +
                "AND NOT EXISTS (SELECT 1 FROM team_rsvps r WHERE r.meetup_id = ? AND r.user_id = m.user_id)",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("full_name"));
                    m.put("availability", split(rs.getString("availability")));
                    m.put("side", rs.getString("side"));
                    m.put("token", rs.getObject("token", UUID.class));
                    m.put("email", rs.getString("email"));
                    return m;
                }, id);

        int sent = 0, skipped = 0;
        for (Map<String, Object> m : members) {
            @SuppressWarnings("unchecked") List<String> slots = (List<String>) m.get("availability");
            boolean sideFits = "either".equals(side) || "either".equals(m.get("side")) || side.equals(m.get("side"));
            boolean timeFits = slots.isEmpty() || slots.contains(slot);
            if (!all && !(sideFits && timeFits)) { skipped++; continue; }
            UUID token = (UUID) m.get("token");
            String name = (String) m.get("name");
            List<String> paragraphs = new ArrayList<>();
            paragraphs.add("Hi " + firstName(name) + ", " + when(mt) + ".");
            paragraphs.add("Meet at " + mt.get("meetingPoint") + ".");
            if (mt.get("notes") != null && !((String) mt.get("notes")).isBlank()) paragraphs.add((String) mt.get("notes"));
            paragraphs.add("Come if you can. There are no shifts and no quotas, and everyone you sign up uses your code.");
            SimpleEmail.Rendered mail = SimpleEmail.render(
                    mt.get("title") + ", " + DAY.format(starts.atZone(HARLEM)) + ": are you in?",
                    String.valueOf(mt.get("title")),
                    paragraphs,
                    List.of(new Button("I'm in", replyLink(token, id, "going"), true),
                            new Button("Can't make it", replyLink(token, id, "not_going"), false)),
                    footer(token));
            if (email.send((String) m.get("email"), mail.subject(), mail.html(), mail.text(), null, "street_team")) sent++;
        }
        jdbc.update("UPDATE team_meetups SET invited_at = NOW() WHERE meetup_id = ?", id);
        log.info("[StreetTeam] action=invite meetup={} scope={} sent={} skipped={} by={}", id, all ? "all" : "match", sent, skipped, by);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", sent);
        out.put("skipped", skipped);
        return out;
    }

    /** One meetup with everyone who answered, and the sign-ups made during it with going members' codes. */
    public Optional<Map<String, Object>> meetupDetail(UUID id) {
        Optional<Map<String, Object>> found = meetup(id);
        if (found.isEmpty()) return found;
        Map<String, Object> mt = found.get();
        mt.put("replies", jdbc.query(
                "SELECT r.user_id, r.answer, r.attended, r.responded_at, m.full_name, m.phone, u.username, u.referral_code " +
                "FROM team_rsvps r JOIN street_team_members m ON m.user_id = r.user_id JOIN users u ON u.user_id = r.user_id " +
                "WHERE r.meetup_id = ? ORDER BY (r.answer = 'going') DESC, m.full_name",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", rs.getObject("user_id", UUID.class));
                    m.put("name", rs.getString("full_name"));
                    m.put("username", rs.getString("username"));
                    m.put("phone", rs.getString("phone"));
                    m.put("referralCode", rs.getString("referral_code"));
                    m.put("answer", rs.getString("answer"));
                    m.put("attended", rs.getObject("attended"));
                    m.put("respondedAt", iso(rs.getTimestamp("responded_at")));
                    return m;
                }, id));
        // referrals.created_at is written in the server's local time (LocalDateTime.now()).
        ZoneId server = ZoneId.systemDefault();
        LocalDateTime from = LocalDateTime.ofInstant(Instant.parse((String) mt.get("startsAt")).minusSeconds(30 * 60), server);
        LocalDateTime to = LocalDateTime.ofInstant(Instant.parse((String) mt.get("endsAt")).plusSeconds(2 * 3600), server);
        mt.put("signupsDuring", jdbc.queryForObject(
                "SELECT COUNT(*) FROM referrals ref JOIN users n ON n.user_id = ref.referred_id AND n.deleted_at IS NULL " +
                "WHERE ref.referrer_id IN (SELECT user_id FROM team_rsvps WHERE meetup_id = ? AND (answer = 'going' OR attended = TRUE)) " +
                "AND ref.created_at BETWEEN ? AND ?",
                Long.class, id, Timestamp.valueOf(from), Timestamp.valueOf(to)));
        return Optional.of(mt);
    }

    @Transactional
    public void setAttendance(UUID meetupId, UUID userId, boolean came) {
        int n = jdbc.update(
                "INSERT INTO team_rsvps (meetup_id, user_id, answer, attended) " +
                "SELECT ?, ?, 'going', ? WHERE EXISTS (SELECT 1 FROM street_team_members WHERE user_id = ?) " +
                "ON CONFLICT (meetup_id, user_id) DO UPDATE SET attended = EXCLUDED.attended",
                meetupId, userId, came, userId);
        if (n == 0) throw new IllegalArgumentException("That person isn't on the street team.");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Reminders (TeamReminderScheduler)
    // ═════════════════════════════════════════════════════════════════════════

    /** Emails everyone going to a meetup that starts in the next 20 to 28 hours. Once per meetup. */
    public int sendReminders() {
        List<UUID> due = jdbc.query(
                "SELECT meetup_id FROM team_meetups WHERE status = 'scheduled' AND reminded_at IS NULL " +
                "AND starts_at BETWEEN NOW() + INTERVAL '20 hours' AND NOW() + INTERVAL '28 hours'",
                (rs, i) -> rs.getObject(1, UUID.class));
        int sent = 0;
        for (UUID id : due) {
            Map<String, Object> mt = meetup(id).orElse(null);
            if (mt == null) continue;
            // Claim it first, so a second server instance can't send it again.
            int claimed = jdbc.update("UPDATE team_meetups SET reminded_at = NOW() WHERE meetup_id = ? AND reminded_at IS NULL", id);
            if (claimed == 0) continue;
            for (Recipient r : recipients(id, "going")) {
                SimpleEmail.Rendered mail = SimpleEmail.render(
                        "Tomorrow: " + mt.get("title"),
                        "Tomorrow: " + mt.get("title"),
                        List.of("Hi " + firstName(r.name) + ", see you " + when(mt) + ".",
                                "Meet at " + mt.get("meetingPoint") + ". Bring your phone with your code ready.",
                                "Plans changed? Tap below so we know."),
                        List.of(new Button("Can't make it after all", replyLink(r.token, id, "not_going"), false)),
                        footer(r.token));
                if (email.send(r.email, mail.subject(), mail.html(), mail.text(), null, "street_team")) sent++;
            }
        }
        if (!due.isEmpty()) log.info("[StreetTeam] action=reminders meetups={} sent={}", due.size(), sent);
        return sent;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private record Recipient(String name, String email, UUID token) {}

    private record Fields(String title, String meetingPoint, Instant startsAt, Instant endsAt, String side,
                          Integer spots, String notes) {}

    private List<Recipient> recipients(UUID meetupId, String answer) {
        return jdbc.query(
                "SELECT m.full_name, u.email, m.token FROM team_rsvps r " +
                "JOIN street_team_members m ON m.user_id = r.user_id JOIN users u ON u.user_id = r.user_id " +
                "WHERE r.meetup_id = ? AND r.answer = ? AND u.deleted_at IS NULL",
                (rs, i) -> new Recipient(rs.getString("full_name"), rs.getString("email"), rs.getObject("token", UUID.class)),
                meetupId, answer);
    }

    private Optional<Map<String, Object>> meetup(UUID id) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT t.*, (SELECT COUNT(*) FROM team_rsvps x WHERE x.meetup_id = t.meetup_id AND x.answer = 'going') AS going " +
                "FROM team_meetups t WHERE t.meetup_id = ?",
                (rs, i) -> meetupRow(rs, false), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Map<String, Object> meetupRow(ResultSet rs, boolean withMyAnswer) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("meetupId", rs.getObject("meetup_id", UUID.class));
        m.put("title", rs.getString("title"));
        m.put("meetingPoint", rs.getString("meeting_point"));
        m.put("startsAt", iso(rs.getTimestamp("starts_at")));
        m.put("endsAt", iso(rs.getTimestamp("ends_at")));
        m.put("side", rs.getString("side"));
        m.put("spots", rs.getObject("spots"));
        m.put("notes", rs.getString("notes"));
        m.put("status", rs.getString("status"));
        m.put("going", rs.getLong("going"));
        if (withMyAnswer) m.put("myAnswer", rs.getString("my_answer"));
        return m;
    }

    private Fields fields(Map<String, Object> body) {
        String title = text(body.get("title"), 2, 120, "a name for the meetup, like Wagner Houses");
        String point = text(body.get("meetingPoint"), 2, 200, "where to meet");
        Instant starts = harlemTime(body.get("startsAt"), "the start time");
        Instant ends = harlemTime(body.get("endsAt"), "the end time");
        if (!ends.isAfter(starts)) throw new IllegalArgumentException("The end time has to be after the start time.");
        if (ends.isBefore(Instant.now())) throw new IllegalArgumentException("That time has already passed.");
        String side = String.valueOf(body.getOrDefault("side", "either"));
        if (!SIDES.contains(side)) side = "either";
        Integer spots = null;
        Object s = body.get("spots");
        if (s != null && !String.valueOf(s).isBlank()) {
            try {
                spots = Integer.valueOf(String.valueOf(s).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Spots must be a number.");
            }
            if (spots < 1 || spots > 500) throw new IllegalArgumentException("Spots must be between 1 and 500, or left blank.");
        }
        String notes = body.get("notes") == null ? null : String.valueOf(body.get("notes")).trim();
        if (notes != null && notes.length() > 1000) throw new IllegalArgumentException("Keep the notes under 1,000 characters.");
        if (notes != null && notes.isEmpty()) notes = null;
        return new Fields(title, point, starts, ends, side, spots, notes);
    }

    private static String text(Object raw, int min, int max, String what) {
        String v = raw == null ? "" : String.valueOf(raw).trim();
        if (v.length() < min || v.length() > max) throw new IllegalArgumentException("Enter " + what + ".");
        return v;
    }

    /** "2026-11-07T12:00" (New York time) or a full ISO instant. */
    private static Instant harlemTime(Object raw, String what) {
        String v = raw == null ? "" : String.valueOf(raw).trim();
        try {
            if (v.endsWith("Z") || v.matches(".*[+-]\\d\\d:\\d\\d$")) return ZonedDateTime.parse(v).toInstant();
            return LocalDateTime.parse(v).atZone(HARLEM).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Enter " + what + ".");
        }
    }

    /** weekday_day, weekday_evening, weekend_day or weekend_evening, in New York time. Evening starts at 5 PM. */
    static String slotOf(Instant start) {
        ZonedDateTime t = start.atZone(HARLEM);
        boolean weekend = t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY;
        return (weekend ? "weekend" : "weekday") + (t.getHour() >= 17 ? "_evening" : "_day");
    }

    private static String when(Map<String, Object> mt) {
        ZonedDateTime s = Instant.parse((String) mt.get("startsAt")).atZone(HARLEM);
        ZonedDateTime e = Instant.parse((String) mt.get("endsAt")).atZone(HARLEM);
        return DAY.format(s) + ", " + TIME.format(s) + " to " + TIME.format(e);
    }

    private static String cleanList(List<String> values, Set<String> allowed) {
        if (values == null) return "";
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) if (v != null && allowed.contains(v)) out.add(v);
        return String.join(",", out);
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    private static String iso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) return "there";
        return name.trim().split("\\s+")[0];
    }

    private String base() {
        return frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
    }

    private String memberLink(UUID token) {
        return base() + "/team/me?t=" + token;
    }

    private String replyLink(UUID token, UUID meetupId, String answer) {
        return memberLink(token) + "&m=" + meetupId + "&a=" + answer;
    }

    private String footer(UUID token) {
        return "You're on the Unis street team. To stop these emails, open " + memberLink(token)
                + " and turn street team emails off.";
    }

    // ── Join emails ─────────────────────────────────────────────────────────

    private void sendConfirm(String to, String name, UUID token) {
        SimpleEmail.Rendered mail = SimpleEmail.render(
                "Confirm your spot on the Unis street team",
                "One tap and you're on the team",
                List.of("Hi " + firstName(name) + ", thanks for joining the Unis street team.",
                        "Confirm below to see upcoming meetups and your code. There are no shifts and no quotas: "
                        + "you come when you can, and everyone you bring in uses your code."),
                List.of(new Button("Confirm", memberLink(token) + "&confirm=1", true)),
                "You got this because this email was entered on the Unis street team form. "
                + "If that wasn't you, ignore it and nothing happens.");
        email.send(to, mail.subject(), mail.html(), mail.text(), null, "street_team");
    }

    private void sendAlreadyMember(String to, String name, UUID token) {
        SimpleEmail.Rendered mail = SimpleEmail.render(
                "You're already on the Unis street team",
                "You're already on the team",
                List.of("Hi " + firstName(name) + ", you're already a street team member. "
                        + "Your page has your code and every upcoming meetup."),
                List.of(new Button("Open your street team page", memberLink(token), true)),
                "You got this because this email was entered on the Unis street team form.");
        email.send(to, mail.subject(), mail.html(), mail.text(), null, "street_team");
    }

    private void sendNoAccount(String to, String name) {
        String code = ReferralCodeService.normalize(signupCode);
        String joinUrl = base() + (code.isEmpty() ? "/join" : "/join/" + code);
        SimpleEmail.Rendered mail = SimpleEmail.render(
                "Make your Unis account first",
                "Make your Unis account first",
                List.of("Hi " + firstName(name) + ", the street team is for Unis members, and there's no account with this email yet.",
                        "Create your account, then fill in the street team form again with the same email."),
                List.of(new Button("Create your account", joinUrl, true),
                        new Button("Street team form", base() + "/team/join", false)),
                "You got this because this email was entered on the Unis street team form. "
                + "If that wasn't you, ignore it and nothing happens.");
        email.send(to, mail.subject(), mail.html(), mail.text(), null, "street_team");
    }

    private void sendUnderAge(String to, String name) {
        SimpleEmail.Rendered mail = SimpleEmail.render(
                "About the Unis street team",
                "The street team is for adults",
                List.of("Hi " + firstName(name) + ", thanks for wanting to help. The street team is for people "
                        + MIN_AGE + " and older, so we can't add you yet.",
                        "You can still share your own code with friends from your Unis profile."),
                List.of(),
                "You got this because this email was entered on the Unis street team form.");
        email.send(to, mail.subject(), mail.html(), mail.text(), null, "street_team");
    }
}
