package com.unis.service;

import com.unis.service.email.SimpleEmail;
import com.unis.service.email.SimpleEmail.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Winner announcements.
 *
 *   - card(awardId): everything a share card needs (title, winner, period,
 *     photo, caption). Public: it backs the /award/:id page people share.
 *   - announcements(date): every card for awards whose period ended that day
 *     (Admin → Announcements), so Unis can post them.
 *   - notifyNewWinners(): emails each new winner (WinnerNoticeScheduler, a few
 *     minutes after RaceCloseScheduler closes races). awards.winner_notified_at
 *     marks who's been told; each award is claimed before sending, so it's
 *     emailed once even with two server instances.
 *
 * Photos follow the same rule as profile galleries: a winner under
 * ArtistPhotoService.PUBLIC_PHOTO_MIN_AGE is shown by name only.
 *
 * Captions state the award exactly ("Uptown Harlem's Rap Artist of the Day")
 * and never stronger claims like "the #1 rapper in Harlem".
 */
@Service
public class AwardCardService {

    private static final Logger log = LoggerFactory.getLogger(AwardCardService.class);
    private static final ZoneId HARLEM = ZoneId.of("America/New_York");

    private static final Map<String, String> UNIT = Map.of(
            "Daily", "Day", "Weekly", "Week", "Monthly", "Month",
            "Quarterly", "Quarter", "Midterm", "Half-Year", "Annual", "Year");

    private final JdbcTemplate jdbc;
    private final EmailService email;

    @Value("${app.frontend.url:https://unismusic.com}")
    private String frontendUrl;

    @Value("${unis.api-base-url:http://localhost:8080}")
    private String apiBaseUrl;

    @Value("${UNIS_WINNER_EMAIL_LIMIT:80}")
    private int emailLimitPerRun;

    public AwardCardService(JdbcTemplate jdbc, EmailService email) {
        this.jdbc = jdbc;
        this.email = email;
    }

    private static final String CARD_SQL =
            "SELECT a.award_id, a.target_type, a.award_date, g.name AS genre, j.name AS jurisdiction, " +
            "  vi.name AS interval_name, s.song_id, s.title AS song_title, s.artwork_url, " +
            "  u.user_id AS artist_id, u.username, u.photo_url, u.date_of_birth, u.email, " +
            "  u.email_notifications, u.unsubscribe_token " +
            "FROM awards a " +
            "JOIN jurisdictions j ON j.jurisdiction_id = a.jurisdiction_id " +
            "JOIN voting_intervals vi ON vi.interval_id = a.interval_id " +
            "LEFT JOIN genres g ON g.genre_id = a.genre_id " +
            "LEFT JOIN songs s ON a.target_type = 'song' AND s.song_id = a.target_id " +
            "JOIN users u ON u.user_id = CASE WHEN a.target_type = 'song' THEN s.artist_id ELSE a.target_id END " +
            "WHERE u.deleted_at IS NULL AND a.target_type IN ('artist', 'song') ";

    /** The share card for one award, or empty if it's gone (or the winner's account is). */
    public Optional<Map<String, Object>> card(UUID awardId) {
        List<Map<String, Object>> rows = jdbc.query(CARD_SQL + "AND a.award_id = ?", (rs, i) -> row(rs), awardId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(publicView(rows.get(0)));
    }

    /** Every card for awards whose period ended on this day, biggest award first. */
    public List<Map<String, Object>> announcements(LocalDate date) {
        return jdbc.query(CARD_SQL + "AND a.award_date = ? " +
                        "ORDER BY vi.duration_days DESC, j.name, g.name, a.target_type",
                (rs, i) -> publicView(row(rs)), date);
    }

    /** Yesterday in Harlem: the day whose winners the morning posts announce. */
    public static LocalDate yesterday() {
        return LocalDate.now(HARLEM).minusDays(1);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Winner emails (WinnerNoticeScheduler)
    // ═════════════════════════════════════════════════════════════════════════

    public int notifyNewWinners() {
        if (!email.isConfigured()) return 0;
        // Only recent awards, so a long outage can't send a pile of old news.
        List<UUID> pending = jdbc.query(
                "SELECT award_id FROM awards WHERE winner_notified_at IS NULL " +
                "AND created_at >= NOW() - INTERVAL '3 days' ORDER BY created_at LIMIT ?",
                (rs, i) -> rs.getObject(1, UUID.class), Math.max(1, emailLimitPerRun));
        int sent = 0, skipped = 0, failed = 0;
        for (UUID id : pending) {
            int claimed = jdbc.update(
                    "UPDATE awards SET winner_notified_at = NOW() WHERE award_id = ? AND winner_notified_at IS NULL", id);
            if (claimed == 0) continue;
            List<Map<String, Object>> rows = jdbc.query(CARD_SQL + "AND a.award_id = ?", (rs, i) -> row(rs), id);
            if (rows.isEmpty()) { skipped++; continue; }
            Map<String, Object> r = rows.get(0);
            if (!Boolean.TRUE.equals(r.get("emailNotifications")) || r.get("email") == null) { skipped++; continue; }
            if (sendWinnerEmail(r)) {
                sent++;
            } else {
                failed++;
                // Let the next run try again (it stops after 3 days).
                jdbc.update("UPDATE awards SET winner_notified_at = NULL WHERE award_id = ?", id);
            }
        }
        if (!pending.isEmpty()) {
            log.info("[WinnerEmail] action=run awards={} sent={} skipped={} failed={}", pending.size(), sent, skipped, failed);
        }
        return sent;
    }

    private boolean sendWinnerEmail(Map<String, Object> r) {
        String title = (String) r.get("title");
        String name = (String) r.get("username");
        boolean song = "song".equals(r.get("targetType"));
        String subject = song
                ? "“" + r.get("songTitle") + "” is " + title
                : "You're " + title;
        String pageUrl = base() + "/award/" + r.get("awardId");
        SimpleEmail.Rendered mail = SimpleEmail.render(
                subject,
                song ? "“" + r.get("songTitle") + "” won" : "You won",
                List.of("Congratulations, " + name + ". " + subject + ", for " + r.get("periodLabel") + ".",
                        r.get("jurisdiction") + "'s votes, plays and likes decided it.",
                        "Your award page has a card made for Instagram and TikTok. Share it so your fans see it."),
                List.of(new Button("Open your award page", pageUrl, true)),
                "You got this because you won an award on Unis. To stop Unis emails, use the unsubscribe link "
                        + "in this email or turn email updates off in your account settings.");
        Object token = r.get("unsubscribeToken");
        Map<String, String> headers = token == null ? null : Map.of(
                "List-Unsubscribe", "<" + apiBaseUrl + "/api/v1/users/unsubscribe?token=" + token + ">",
                "List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        return email.send((String) r.get("email"), mail.subject(), mail.html(), mail.text(), headers, "winner");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        UUID awardId = rs.getObject("award_id", UUID.class);
        String interval = rs.getString("interval_name");
        String targetType = rs.getString("target_type");
        String genre = capitalize(rs.getString("genre"));
        String jurisdiction = rs.getString("jurisdiction");
        LocalDate awardDate = rs.getObject("award_date", LocalDate.class);
        LocalDate dob = rs.getObject("date_of_birth", LocalDate.class);
        boolean minor = dob != null && Period.between(dob, LocalDate.now(HARLEM)).getYears() < ArtistPhotoService.PUBLIC_PHOTO_MIN_AGE;
        String unit = UNIT.getOrDefault(interval, "Day");
        String kind = "song".equals(targetType) ? "Song" : "Artist";
        String title = jurisdiction + "'s " + (genre.isEmpty() ? "" : genre + " ") + kind + " of the " + unit;

        m.put("awardId", awardId);
        m.put("targetType", targetType);
        m.put("interval", interval);
        m.put("unit", unit);
        m.put("genre", genre);
        m.put("jurisdiction", jurisdiction);
        m.put("awardDate", awardDate == null ? null : awardDate.toString());
        m.put("periodLabel", periodLabel(interval, awardDate));
        m.put("title", title);
        m.put("artistId", rs.getObject("artist_id", UUID.class));
        m.put("username", rs.getString("username"));
        m.put("songId", rs.getObject("song_id", UUID.class));
        m.put("songTitle", rs.getString("song_title"));
        m.put("photoUrl", minor ? null : rs.getString("photo_url"));
        m.put("artworkUrl", rs.getString("artwork_url"));
        // Private fields, used only for the winner email.
        m.put("email", rs.getString("email"));
        m.put("emailNotifications", rs.getObject("email_notifications"));
        m.put("unsubscribeToken", rs.getObject("unsubscribe_token"));
        return m;
    }

    /** What anyone may see: drops the email fields and adds the share link and caption. */
    private Map<String, Object> publicView(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.remove("email");
        m.remove("emailNotifications");
        m.remove("unsubscribeToken");
        String pageUrl = base() + "/award/" + r.get("awardId");
        m.put("pageUrl", pageUrl);
        m.put("caption", caption(r, pageUrl));
        return m;
    }

    private static String caption(Map<String, Object> r, String pageUrl) {
        String jurisdiction = (String) r.get("jurisdiction");
        String winner = "song".equals(r.get("targetType"))
                ? "“" + r.get("songTitle") + "” by " + r.get("username")
                : String.valueOf(r.get("username"));
        return r.get("title") + ": " + winner + ". " + jurisdiction + "'s votes, plays and likes decided it. "
                + "Congrats for representing " + jurisdiction + ".\n\nHear it on Unis: " + pageUrl;
    }

    static String periodLabel(String interval, LocalDate end) {
        if (end == null) return "";
        LocalDate[] p = RaceClockService.periodContaining(interval, end);
        DateTimeFormatter md = DateTimeFormatter.ofPattern("MMM d", Locale.US);
        return switch (interval == null ? "" : interval) {
            case "Weekly" -> "the week of " + md.format(p[0]) + ", " + p[0].getYear();
            case "Monthly" -> DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US).format(p[0]);
            case "Quarterly" -> "Q" + ((p[0].getMonthValue() - 1) / 3 + 1) + " " + p[0].getYear();
            case "Midterm" -> (p[0].getMonthValue() == 1 ? "January to June " : "July to December ") + p[0].getYear();
            case "Annual" -> String.valueOf(p[0].getYear());
            default -> DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US).format(end);
        };
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return "";
        String t = s.trim();
        return t.substring(0, 1).toUpperCase(Locale.US) + t.substring(1);
    }

    private String base() {
        return frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
    }
}
