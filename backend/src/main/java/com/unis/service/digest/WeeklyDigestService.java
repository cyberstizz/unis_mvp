package com.unis.service.digest;

import com.unis.dto.RaceStandingsDto;
import com.unis.entity.VotingInterval;
import com.unis.repository.VotingIntervalRepository;
import com.unis.service.RaceClockService;
import com.unis.service.RaceStandingsService;
import com.unis.service.RankingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds each reader's Sunday email from the same live standings the app
 * shows (RaceStandingsService), so the email and the Charts page never
 * disagree.
 *
 * <p><b>The reader's area</b> is their home jurisdiction. If nobody there has
 * scored this week but the area one level up has (an Uptown Harlem reader
 * when only Downtown Harlem artists have been played), the email shows the
 * area one level up instead of an empty chart. Never higher than one level,
 * and never all of Unis, which has its own line.
 *
 * <p><b>Dates</b> are the reader's own: the week is Monday to Sunday on their
 * clock, and "closes tonight" is true when their today is that Sunday.
 */
@Service
public class WeeklyDigestService {

    /** One person the email can go to. */
    public record Recipient(UUID userId, String username, String email, boolean artist,
                            UUID jurisdictionId, UUID unsubscribeToken) {}

    /** Boards and names fetched during one run, so 300 readers in one area cost one lookup. */
    public static final class Run {
        private final Map<String, RaceStandingsDto> boards = new HashMap<>();
        private final Map<UUID, Place> places = new HashMap<>();
        private Place root;
        private boolean rootLoaded;
    }

    private record Place(UUID id, String name, UUID parentId) {}

    private static final String RECIPIENT_COLUMNS = """
        SELECT u.user_id, u.username, u.email, u.role::text AS role,
               u.jurisdiction_id, u.unsubscribe_token
        FROM users u
        """;

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private final JdbcTemplate jdbc;
    private final RaceStandingsService standingsService;
    private final RankingService rankingService;
    private final RaceClockService raceClock;
    private final VotingIntervalRepository votingIntervalRepository;

    private final String appBaseUrl;
    private final String apiBaseUrl;
    private final String mediaBaseUrl;
    private final String postalAddress;

    public WeeklyDigestService(JdbcTemplate jdbc,
                               RaceStandingsService standingsService,
                               RankingService rankingService,
                               RaceClockService raceClock,
                               VotingIntervalRepository votingIntervalRepository,
                               @Value("${unis.app-base-url:http://localhost:5173}") String appBaseUrl,
                               @Value("${unis.api-base-url:http://localhost:8080}") String apiBaseUrl,
                               @Value("${unis.media-base-url:https://cdn.unismusic.com}") String mediaBaseUrl,
                               @Value("${unis.mail.postal-address:}") String postalAddress) {
        this.jdbc = jdbc;
        this.standingsService = standingsService;
        this.rankingService = rankingService;
        this.raceClock = raceClock;
        this.votingIntervalRepository = votingIntervalRepository;
        this.appBaseUrl = trimSlash(appBaseUrl);
        this.apiBaseUrl = trimSlash(apiBaseUrl);
        this.mediaBaseUrl = trimSlash(mediaBaseUrl);
        this.postalAddress = postalAddress == null ? "" : postalAddress.trim();
    }

    // =========================================================================
    // RECIPIENTS
    // =========================================================================

    /** Everyone who gets the Sunday email: opted in, email confirmed, account active. */
    public List<Recipient> recipients() {
        return jdbc.query(RECIPIENT_COLUMNS + """
            WHERE u.email_notifications = TRUE
              AND u.email_verified = TRUE
              AND u.deleted_at IS NULL
              AND u.email IS NOT NULL AND u.email <> ''
            ORDER BY u.created_at
            """, (rs, i) -> toRecipient(rs));
    }

    /** One account, opted in or not — for the admin preview and test send. */
    public Optional<Recipient> recipient(UUID userId) {
        List<Recipient> found = jdbc.query(RECIPIENT_COLUMNS + " WHERE u.user_id = ? AND u.deleted_at IS NULL",
            (rs, i) -> toRecipient(rs), userId);
        return found.stream().findFirst();
    }

    private static Recipient toRecipient(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Recipient(
            rs.getObject("user_id", UUID.class),
            rs.getString("username"),
            rs.getString("email"),
            "artist".equalsIgnoreCase(rs.getString("role")),
            rs.getObject("jurisdiction_id", UUID.class),
            rs.getObject("unsubscribe_token", UUID.class));
    }

    public Run newRun() {
        return new Run();
    }

    // =========================================================================
    // BUILD
    // =========================================================================

    public WeeklyDigest build(Recipient r, Run run) {
        UUID weekly = weeklyIntervalId();
        ZoneId zone = raceClock.zoneOfUser(r.userId());
        LocalDate today = raceClock.today(zone);
        LocalDate[] week = RaceClockService.periodContaining("Weekly", today);
        boolean closesTonight = today.equals(week[1]);

        Place root = root(run);
        Place home = r.jurisdictionId() != null ? place(run, r.jurisdictionId()) : root;
        if (home == null) home = root;
        if (home == null) throw new IllegalStateException("no home jurisdiction and no Unis root");

        // ── The reader's area ────────────────────────────────────────────────
        List<Place> choices = new ArrayList<>();
        choices.add(home);
        if (home != null && home.parentId() != null && (root == null || !home.parentId().equals(root.id()))) {
            Place parent = place(run, home.parentId());
            if (parent != null) choices.add(parent);
        }

        Place area = null;
        RaceStandingsDto areaBoard = null;
        for (Place p : choices) {                       // first: an area with points this week
            RaceStandingsDto b = board(run, "artist", p.id(), weekly, 5, today);
            if (RaceStandingsService.LIVE.equals(b.getState()) && scored(b)) { area = p; areaBoard = b; break; }
        }
        if (area == null) {                             // then: an area with last week's final
            for (Place p : choices) {
                RaceStandingsDto b = board(run, "artist", p.id(), weekly, 5, today);
                if (scored(b)) { area = p; areaBoard = b; break; }
            }
        }
        if (area == null) {                             // nothing anywhere: the home area, empty
            area = home;
            areaBoard = board(run, "artist", home.id(), weekly, 5, today);
        }
        boolean areaLive = RaceStandingsService.LIVE.equals(areaBoard.getState());

        // ── Artists ──────────────────────────────────────────────────────────
        List<RaceStandingsDto.Entry> scoredArtists = areaBoard.getEntries().stream()
            .filter(e -> e.getTotalPoints() > 0).toList();
        List<WeeklyDigest.Pick> topFive = new ArrayList<>();
        for (int i = 0; i < scoredArtists.size(); i++) {
            Integer lead = null;
            if (i == 0 && scoredArtists.size() > 1) {
                lead = scoredArtists.get(0).getTotalPoints() - scoredArtists.get(1).getTotalPoints();
            }
            topFive.add(pick(scoredArtists.get(i), lead));
        }
        WeeklyDigest.Pick topArtist = topFive.isEmpty() ? null : topFive.get(0);

        // ── The number one song, only when it's from the same week as the artists ──
        WeeklyDigest.Pick topSong = null;
        RaceStandingsDto songBoard = board(run, "song", area.id(), weekly, 1, today);
        if (songBoard.getState().equals(areaBoard.getState()) && scored(songBoard)) {
            topSong = pick(songBoard.getEntries().get(0), null);
        }

        // ── Number one on all of Unis ────────────────────────────────────────
        WeeklyDigest.Pick unisArtist = null;
        boolean unisLive = false;
        if (root != null && !root.id().equals(area.id())) {
            RaceStandingsDto unisBoard = board(run, "artist", root.id(), weekly, 1, dayForRace(root.id(), today));
            if (scored(unisBoard)) {
                unisArtist = pick(unisBoard.getEntries().get(0), null);
                unisLive = RaceStandingsService.LIVE.equals(unisBoard.getState());
            }
        }

        // ── The reader's own week ────────────────────────────────────────────
        WeeklyDigest.Personal personal;
        if (r.artist()) {
            Integer rank = null;
            if (areaLive) {
                for (WeeklyDigest.Pick p : topFive) {
                    if (p.id().equals(r.userId())) { rank = p.rank(); break; }
                }
                if (rank == null) {
                    rank = rankingService.positions("artist", area.id(), null,
                        areaBoard.getPeriodStart(), areaBoard.getPeriodEnd(), List.of(r.userId())).get(r.userId());
                }
            }
            personal = new WeeklyDigest.Personal(rank, 0, 0);
        } else {
            LocalDateTime from = RaceClockService.toUtcWall(week[0].atStartOfDay(zone).toInstant());
            LocalDateTime to = RaceClockService.toUtcWall(week[1].plusDays(1).atStartOfDay(zone).toInstant());
            Integer plays = jdbc.queryForObject(
                "SELECT COUNT(*) FROM song_plays WHERE user_id = ? AND played_at >= ? AND played_at < ?",
                Integer.class, r.userId(), from, to);
            Integer votes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM votes WHERE user_id = ? AND vote_date BETWEEN ? AND ?",
                Integer.class, r.userId(), week[0], week[1]);
            personal = new WeeklyDigest.Personal(null, plays == null ? 0 : plays, votes == null ? 0 : votes);
        }

        return new WeeklyDigest(
            r.username(),
            r.artist(),
            area.name(),
            placeKey(area.name()),
            weekLabel(week),
            closesTonight,
            areaLive,
            unisLive,
            topArtist,
            topSong,
            unisArtist,
            topFive,
            personal,
            appBaseUrl,
            unsubscribeUrl(r),
            postalAddress);
    }

    public String unsubscribeUrl(Recipient r) {
        return apiBaseUrl + "/api/v1/users/unsubscribe?token=" + r.unsubscribeToken();
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private RaceStandingsDto board(Run run, String type, UUID jurisdictionId, UUID intervalId, int limit, LocalDate day) {
        String key = type + '|' + jurisdictionId + '|' + limit + '|' + day;
        return run.boards.computeIfAbsent(key, k ->
            standingsService.standings(type, jurisdictionId, null, intervalId, limit, 0, day));
    }

    private static boolean scored(RaceStandingsDto b) {
        return b.getEntries() != null && !b.getEntries().isEmpty() && b.getEntries().get(0).getTotalPoints() > 0;
    }

    /** The reader's day, held inside the range of days a race's people are on. */
    private LocalDate dayForRace(UUID raceId, LocalDate readerDay) {
        LocalDate newest = raceClock.newestLocalDate(raceId);
        LocalDate oldest = raceClock.oldestLocalDate(raceId);
        if (readerDay.isAfter(newest)) return newest;
        if (readerDay.isBefore(oldest)) return oldest;
        return readerDay;
    }

    private UUID weeklyIntervalId() {
        return votingIntervalRepository.findByName("Weekly")
            .map(VotingInterval::getIntervalId)
            .orElseThrow(() -> new IllegalStateException("No Weekly voting interval"));
    }

    private Place place(Run run, UUID id) {
        if (id == null) return null;
        return run.places.computeIfAbsent(id, k -> {
            List<Place> rows = jdbc.query(
                "SELECT jurisdiction_id, name, parent_jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = ?",
                (rs, i) -> new Place(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)), k);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /** The top of the tree: all of Unis. */
    private Place root(Run run) {
        if (!run.rootLoaded) {
            List<Place> rows = jdbc.query("""
                SELECT jurisdiction_id, name, parent_jurisdiction_id FROM jurisdictions
                WHERE parent_jurisdiction_id IS NULL
                ORDER BY (name = 'Unis') DESC, created_at
                LIMIT 1
                """, (rs, i) -> new Place(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)));
            run.root = rows.isEmpty() ? null : rows.get(0);
            run.rootLoaded = true;
        }
        return run.root;
    }

    private WeeklyDigest.Pick pick(RaceStandingsDto.Entry e, Integer leadOverNext) {
        return new WeeklyDigest.Pick(
            e.getTargetId(),
            e.getTargetType(),
            e.getRank(),
            e.getName() == null ? "Unknown" : e.getName(),
            e.getArtistName(),
            e.getArtistId(),
            absoluteMedia(e.getArtworkUrl()),
            e.getTotalPoints(),
            e.getVotes(),
            e.getPlays(),
            e.getLikes(),
            e.getMovement(),
            e.isNewEntry(),
            leadOverNext);
    }

    /**
     * An image link a mail app can load. Same rules as the web app's buildUrl:
     * private R2 links are rewritten to the public CDN, full links pass
     * through, and relative paths are served from the API's origin.
     */
    String absoluteMedia(String url) {
        if (url == null || url.isBlank()) return null;
        String u = url.trim();
        if (u.contains("r2.cloudflarestorage.com")) {
            int bucket = u.indexOf("/unis-media/uploads/");
            if (bucket >= 0) return encodeSpaces(mediaBaseUrl + u.substring(bucket + "/unis-media".length()));
            int uploads = u.indexOf("/uploads/");
            if (uploads >= 0) return encodeSpaces(mediaBaseUrl + u.substring(uploads));
            try {
                return encodeSpaces(mediaBaseUrl + URI.create(u.replace(" ", "%20")).getRawPath());
            } catch (Exception ex) {
                return null;
            }
        }
        if (u.startsWith("http")) return encodeSpaces(u);
        String origin = apiBaseUrl.replaceAll("/api/?$", "");
        return encodeSpaces(origin + (u.startsWith("/") ? "" : "/") + u);
    }

    private static String encodeSpaces(String s) {
        return s.replace(" ", "%20");
    }

    /** "Oct 12 – 18", "Sep 28 – Oct 4". */
    static String weekLabel(LocalDate[] week) {
        String start = week[0].format(MONTH_DAY);
        String end = week[0].getMonth() == week[1].getMonth()
            ? String.valueOf(week[1].getDayOfMonth())
            : week[1].format(MONTH_DAY);
        return start + " – " + end;
    }

    /** The Charts page's place key: "Uptown Harlem" → "uptown-harlem". */
    static String placeKey(String name) {
        if (name == null) return null;
        String key = name.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return key.isEmpty() ? null : key;
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.trim().replaceAll("/+$", "");
    }
}
