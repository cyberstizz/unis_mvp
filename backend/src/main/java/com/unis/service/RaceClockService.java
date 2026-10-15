package com.unis.service;

import com.unis.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Unis race clock — the one place that decides what day it is.
 *
 * <p>Every jurisdiction runs on its real-world clock (jurisdictions.timezone,
 * see sql/V2026_10e__race_clock.sql). The rules:
 *
 * <ul>
 *   <li><b>A vote counts on the voter's own calendar day.</b> An 11:59 PM vote
 *       in New York is that New York day; at 12:01 AM it is the next day and
 *       the voter can vote again.</li>
 *   <li><b>Plays and likes count on the artist's home calendar day.</b> It is
 *       the artist's race, so a Harlem race can close at midnight New York
 *       time even while someone in Los Angeles is still listening.</li>
 *   <li><b>A race closes when its day has ended everywhere its people live.</b>
 *       "Its people" are everyone whose home is the race's jurisdiction or
 *       somewhere inside it. Harlem closes at midnight New York time. The
 *       Unis-wide race waits for the last zone anyone on Unis lives in — while
 *       everyone is on Eastern time, that is also midnight Eastern.</li>
 * </ul>
 *
 * <p>Server clocks never decide a day: everything here works from instants and
 * zone names, so it does not matter what timezone the server runs in.
 */
@Service
public class RaceClockService {

    private static final Logger log = LoggerFactory.getLogger(RaceClockService.class);

    /** Used only when a jurisdiction or user has no clock on record. */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("America/New_York");

    /** Every voting interval, shortest first. */
    public static final List<String> INTERVAL_NAMES =
        List.of("Daily", "Weekly", "Monthly", "Quarterly", "Midterm", "Annual");

    /**
     * Timestamps (played_at, likes.created_at, deleted_at) are stored as UTC
     * wall-clock time. Real offsets run from UTC-12 to UTC+14, so any local
     * calendar day falls inside [day 00:00 UTC - 14h, next day 00:00 UTC + 14h).
     * Used only to narrow a scan; the exact day is always checked per row.
     */
    private static final int SCAN_SLACK_HOURS = 14;

    private static final Duration ZONE_MAP_TTL = Duration.ofMinutes(10);
    private static final Duration PARTICIPANT_TTL = Duration.ofMinutes(10);
    private static final Duration MIN_FORCED_RELOAD_GAP = Duration.ofMinutes(1);

    private final JdbcTemplate jdbc;

    private volatile Clock clock = Clock.systemUTC();
    private volatile Map<UUID, ZoneId> zoneByJurisdiction = Collections.emptyMap();
    private volatile Instant zoneMapLoadedAt = Instant.EPOCH;
    private volatile Instant lastForcedReload = Instant.EPOCH;
    private final Map<UUID, CachedZones> participantCache = new ConcurrentHashMap<>();

    private record CachedZones(Set<ZoneId> zones, Instant loadedAt) {}

    public RaceClockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tests can pin the clock. Production always uses the system clock. */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    // =========================================================================
    // ZONES
    // =========================================================================

    /** The clock a jurisdiction runs on. */
    public ZoneId zoneOf(UUID jurisdictionId) {
        if (jurisdictionId == null) return DEFAULT_ZONE;
        ZoneId zone = zoneMap(false).get(jurisdictionId);
        if (zone == null) {
            // A jurisdiction created since the last load. Reload, but never
            // more than once a minute, so an unknown ID can't hammer the DB.
            Instant now = now();
            if (Duration.between(lastForcedReload, now).compareTo(MIN_FORCED_RELOAD_GAP) >= 0) {
                lastForcedReload = now;
                zone = zoneMap(true).get(jurisdictionId);
            }
        }
        return zone != null ? zone : DEFAULT_ZONE;
    }

    /** The clock a user lives on: their home jurisdiction's. */
    public ZoneId zoneOfUser(UUID userId) {
        if (userId == null) return DEFAULT_ZONE;
        List<UUID> homes = jdbc.queryForList(
            "SELECT jurisdiction_id FROM users WHERE user_id = ?", UUID.class, userId);
        if (homes.isEmpty() || homes.get(0) == null) return DEFAULT_ZONE;
        return zoneOf(homes.get(0));
    }

    private Map<UUID, ZoneId> zoneMap(boolean forceReload) {
        Instant now = now();
        if (!forceReload && !zoneByJurisdiction.isEmpty()
                && Duration.between(zoneMapLoadedAt, now).compareTo(ZONE_MAP_TTL) < 0) {
            return zoneByJurisdiction;
        }
        synchronized (this) {
            if (!forceReload && !zoneByJurisdiction.isEmpty()
                    && Duration.between(zoneMapLoadedAt, now).compareTo(ZONE_MAP_TTL) < 0) {
                return zoneByJurisdiction;
            }
            Map<UUID, ZoneId> fresh = new HashMap<>();
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT jurisdiction_id, timezone FROM jurisdictions")) {
                UUID id = (UUID) row.get("jurisdiction_id");
                fresh.put(id, parseZone((String) row.get("timezone"), id));
            }
            zoneByJurisdiction = Collections.unmodifiableMap(fresh);
            zoneMapLoadedAt = now;
            return zoneByJurisdiction;
        }
    }

    /**
     * Every clock a race's people live on: the race jurisdiction's own clock
     * plus the clock of every jurisdiction inside it where at least one person
     * lives. Cached for ten minutes per race.
     */
    public Set<ZoneId> participantZones(UUID raceJurisdictionId) {
        Instant now = now();
        CachedZones cached = participantCache.get(raceJurisdictionId);
        if (cached != null && Duration.between(cached.loadedAt(), now).compareTo(PARTICIPANT_TTL) < 0) {
            return cached.zones();
        }
        Set<ZoneId> zones = new LinkedHashSet<>();
        zones.add(zoneOf(raceJurisdictionId));
        for (String tz : jdbc.queryForList(PARTICIPANT_ZONES_SQL, String.class, raceJurisdictionId)) {
            zones.add(parseZone(tz, raceJurisdictionId));
        }
        Set<ZoneId> frozen = Collections.unmodifiableSet(zones);
        participantCache.put(raceJurisdictionId, new CachedZones(frozen, now));
        return frozen;
    }

    private static final String PARTICIPANT_ZONES_SQL = """
        WITH RECURSIVE sub AS (
            SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = ?
            UNION ALL
            SELECT j.jurisdiction_id
            FROM jurisdictions j
            JOIN sub ON j.parent_jurisdiction_id = sub.jurisdiction_id
        )
        SELECT DISTINCT j.timezone
        FROM users u
        JOIN sub ON sub.jurisdiction_id = u.jurisdiction_id
        JOIN jurisdictions j ON j.jurisdiction_id = u.jurisdiction_id
        WHERE u.deleted_at IS NULL
        """;

    private static ZoneId parseZone(String tz, UUID jurisdictionId) {
        if (tz == null || tz.isBlank()) return DEFAULT_ZONE;
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            log.warn("[RaceClock] jurisdiction {} has unrecognised timezone '{}', using {}",
                jurisdictionId, tz, DEFAULT_ZONE);
            return DEFAULT_ZONE;
        }
    }

    // =========================================================================
    // TODAY
    // =========================================================================

    public LocalDate today(ZoneId zone) {
        return LocalDate.ofInstant(now(), zone);
    }

    /** Today on a jurisdiction's own clock. */
    public LocalDate todayFor(UUID jurisdictionId) {
        return today(zoneOf(jurisdictionId));
    }

    /** Today on a user's own clock — the day their vote counts toward. */
    public LocalDate todayForUser(UUID userId) {
        return today(zoneOfUser(userId));
    }

    /** The latest calendar date any of the race's people are on right now. */
    public LocalDate newestLocalDate(UUID raceJurisdictionId) {
        LocalDate newest = null;
        for (ZoneId z : participantZones(raceJurisdictionId)) {
            LocalDate d = today(z);
            if (newest == null || d.isAfter(newest)) newest = d;
        }
        return newest;
    }

    /** The earliest calendar date any of the race's people are still on. */
    public LocalDate oldestLocalDate(UUID raceJurisdictionId) {
        LocalDate oldest = null;
        for (ZoneId z : participantZones(raceJurisdictionId)) {
            LocalDate d = today(z);
            if (oldest == null || d.isBefore(oldest)) oldest = d;
        }
        return oldest;
    }

    /**
     * The day a viewer is looking at a race from: their own today when signed
     * in, held inside the range of days the race's people are on. A Harlem
     * user browsing a Los Angeles race at 1 AM sees Los Angeles's today, not a
     * day Los Angeles hasn't started. Signed out, it is the race's newest day.
     */
    public LocalDate viewerDateForRace(UUID raceJurisdictionId) {
        LocalDate newest = newestLocalDate(raceJurisdictionId);
        Optional<UUID> viewer = currentUserId();
        if (viewer.isEmpty()) return newest;
        LocalDate mine = todayForUser(viewer.get());
        LocalDate oldest = oldestLocalDate(raceJurisdictionId);
        if (mine.isAfter(newest)) return newest;
        if (mine.isBefore(oldest)) return oldest;
        return mine;
    }

    /** The signed-in user on this request, if there is one. */
    public static Optional<UUID> currentUserId() {
        try {
            return Optional.of(SecurityUtils.getAuthenticatedUserId());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // =========================================================================
    // PERIODS
    // =========================================================================

    /**
     * The calendar period an interval's day belongs to: Daily = the day,
     * Weekly = Monday–Sunday, Monthly = the month, Quarterly = Jan–Mar /
     * Apr–Jun / Jul–Sep / Oct–Dec, Midterm = Jan–Jun / Jul–Dec, Annual = the
     * year. Returns {start, end}, both inclusive.
     */
    public static LocalDate[] periodContaining(String intervalName, LocalDate day) {
        String name = intervalName == null ? "" : intervalName;
        switch (name) {
            case "Weekly": {
                LocalDate start = day.with(DayOfWeek.MONDAY);
                return new LocalDate[] { start, start.plusDays(6) };
            }
            case "Monthly": {
                LocalDate start = day.withDayOfMonth(1);
                return new LocalDate[] { start, start.plusMonths(1).minusDays(1) };
            }
            case "Quarterly": {
                int q = (day.getMonthValue() - 1) / 3;
                LocalDate start = day.withMonth(q * 3 + 1).withDayOfMonth(1);
                return new LocalDate[] { start, start.plusMonths(3).minusDays(1) };
            }
            case "Midterm": {
                LocalDate start = day.withMonth(day.getMonthValue() >= 7 ? 7 : 1).withDayOfMonth(1);
                return new LocalDate[] { start, start.plusMonths(6).minusDays(1) };
            }
            case "Annual": {
                LocalDate start = day.withDayOfYear(1);
                return new LocalDate[] { start, start.plusYears(1).minusDays(1) };
            }
            case "Daily":
            default:
                return new LocalDate[] { day, day };
        }
    }

    /** The period immediately before the given one. */
    public static LocalDate[] previousPeriod(String intervalName, LocalDate[] period) {
        return periodContaining(intervalName, period[0].minusDays(1));
    }

    // =========================================================================
    // CLOSING
    // =========================================================================

    /** The moment a race's period ends for the last of its people. */
    public Instant closesAt(UUID raceJurisdictionId, LocalDate periodEnd) {
        Instant latest = null;
        for (ZoneId z : participantZones(raceJurisdictionId)) {
            Instant end = periodEnd.plusDays(1).atStartOfDay(z).toInstant();
            if (latest == null || end.isAfter(latest)) latest = end;
        }
        return latest;
    }

    public boolean isClosed(UUID raceJurisdictionId, LocalDate periodEnd) {
        return !now().isBefore(closesAt(raceJurisdictionId, periodEnd));
    }

    /** Clocks on which this period has not ended yet — empty once it is closed. */
    public List<ZoneId> zonesStillOpen(UUID raceJurisdictionId, LocalDate periodEnd) {
        Instant now = now();
        List<ZoneId> open = new ArrayList<>();
        for (ZoneId z : participantZones(raceJurisdictionId)) {
            if (now.isBefore(periodEnd.plusDays(1).atStartOfDay(z).toInstant())) open.add(z);
        }
        return open;
    }

    /**
     * End date of the most recent period of this interval that has closed for
     * every one of the race's people. The period holding the race's oldest
     * local day is still open somewhere, so the one before it is the latest
     * closed one.
     */
    public LocalDate latestClosedPeriodEnd(UUID raceJurisdictionId, String intervalName) {
        LocalDate[] oldestOpen = periodContaining(intervalName, oldestLocalDate(raceJurisdictionId));
        return oldestOpen[0].minusDays(1);
    }

    // =========================================================================
    // UTC HELPERS (timestamps are stored as UTC wall-clock time)
    // =========================================================================

    public static LocalDateTime scanFromUtc(LocalDate start) {
        return start.atStartOfDay().minusHours(SCAN_SLACK_HOURS);
    }

    public static LocalDateTime scanToUtc(LocalDate end) {
        return end.plusDays(1).atStartOfDay().plusHours(SCAN_SLACK_HOURS);
    }

    public static LocalDateTime toUtcWall(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
