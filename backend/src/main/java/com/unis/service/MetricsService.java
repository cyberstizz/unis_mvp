package com.unis.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Writes the metric snapshots (sql/V2026_10d__metrics.sql).
 *
 *   computeDay(d)     the ledger of who was active on day d, then that day's
 *                     metrics. Replaces the day's rows, so reruns are safe.
 *   writeMonth(ym)    a month's metrics and its cohort rows. Refreshed every
 *                     night while the month is open; never touched once locked.
 *   closeMonth(ym)    locks a month (the official numbers). Reopening a locked
 *                     month needs a written reason, which is logged.
 *   periodRows(a, b)  the same metrics for any date range, computed live and
 *                     not saved (unofficial custom reports).
 *
 * The SQL lives in src/main/resources/metrics/. Days are New York days; the
 * database stores times in the app server's clock (UTC on Railway), so day
 * bounds are converted before querying.
 */
@Service
public class MetricsService {

    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    /** Before launch the job still runs, so the dashboard can be checked; those numbers are marked preview. */
    private static final LocalDate PREVIEW_COHORT_FLOOR = LocalDate.of(2000, 1, 1);

    /** Launch day: the official record starts here. Change with UNIS_METRICS_OFFICIAL_START if launch moves. */
    @Value("${unis.metrics.official-start:2026-11-21}")
    private String officialStartText;

    private volatile LocalDate officialStart;

    /** Counted plays in one day above which an account is flagged for review. */
    @Value("${unis.metrics.suspicious-daily-plays:300}")
    private int suspiciousDailyPlays;

    /** Override only if the database clock is not the app server's clock. */
    @Value("${unis.metrics.db-zone:}")
    private String dbZoneOverride;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private final Map<String, String> sqlCache = new ConcurrentHashMap<>();

    // ── Settings ─────────────────────────────────────────────────────────────

    public LocalDate getOfficialStart() {
        LocalDate d = officialStart;
        if (d == null) {
            d = LocalDate.parse(officialStartText.trim());
            officialStart = d;
        }
        return d;
    }

    public int getDefinitionsVersion() {
        Integer v = jdbc.getJdbcTemplate().queryForObject(
            "SELECT COALESCE(MAX(version), 1) FROM metric_definitions", Integer.class);
        return v != null ? v : 1;
    }

    // ── Days ─────────────────────────────────────────────────────────────────

    /** Rebuilds day d: the ledger first, then the day's metrics. */
    @Transactional
    public void computeDay(LocalDate d) {
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("d", d)
            .addValue("dstart", dbStartOf(d))
            .addValue("dend", dbStartOf(d.plusDays(1)))
            .addValue("wstart", windowStart(d))
            .addValue("suspicious", suspiciousDailyPlays);
        jdbc.update("DELETE FROM user_active_days WHERE activity_date = :d", p);
        jdbc.update(sql("daily_ledger.sql"), p);
        jdbc.update("DELETE FROM metric_daily WHERE metric_date = :d", p);
        jdbc.update(sql("daily_metrics.sql"), p);
    }

    /** The most recent day that has been computed, or null if none. */
    public LocalDate lastComputedDay() {
        return jdbc.getJdbcTemplate().queryForObject(
            "SELECT MAX(metric_date) FROM metric_daily", LocalDate.class);
    }

    // ── Months ───────────────────────────────────────────────────────────────

    public boolean isClosed(YearMonth ym) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM metric_closes WHERE month = :m",
            new MapSqlParameterSource("m", ym.atDay(1)), Integer.class);
        return n != null && n > 0;
    }

    /** Writes a month's metrics and cohort rows. Callers make sure the month is open. */
    @Transactional
    public void writeMonth(YearMonth ym) {
        LocalDate first = ym.atDay(1);
        LocalDate last = ym.atEndOfMonth();
        MapSqlParameterSource p = periodParams(first, last);
        p.addValue("month", first);

        jdbc.update("DELETE FROM metric_monthly WHERE month = :month", p);
        jdbc.update("INSERT INTO metric_monthly (month, metric_key, segment_type, segment_value, value) "
            + "SELECT CAST(:month AS DATE), metric_key, segment_type, segment_value, value FROM ("
            + sql("period_metrics.sql") + ") r", p);

        jdbc.update("INSERT INTO cohort_retention "
            + "(cohort_month, months_since, segment_type, segment_value, cohort_size, active_users, "
            + " listen_hours, gpv_cents, net_revenue_cents) "
            + "SELECT * FROM (" + sql("cohort_rows.sql") + ") c "
            + "ON CONFLICT (cohort_month, months_since, segment_type, segment_value) DO UPDATE SET "
            + "cohort_size = EXCLUDED.cohort_size, active_users = EXCLUDED.active_users, "
            + "listen_hours = EXCLUDED.listen_hours, gpv_cents = EXCLUDED.gpv_cents, "
            + "net_revenue_cents = EXCLUDED.net_revenue_cents, computed_at = now()", p);
    }

    /**
     * Locks a month. A month already locked is only recomputed with a reason
     * (a correction), and the rerun is counted and logged.
     *
     * @return true if the month was written
     */
    @Transactional
    public boolean closeMonth(YearMonth ym, UUID byUserId, String reason) {
        boolean wasClosed = isClosed(ym);
        if (wasClosed && (reason == null || reason.isBlank())) {
            return false;
        }
        writeMonth(ym);

        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("m", ym.atDay(1))
            .addValue("now", LocalDateTime.now())
            .addValue("version", getDefinitionsVersion())
            .addValue("official", !ym.atEndOfMonth().isBefore(getOfficialStart()))
            .addValue("reason", reason)
            .addValue("by", byUserId);
        if (wasClosed) {
            jdbc.update("UPDATE metric_closes SET rerun_count = rerun_count + 1, last_rerun_at = :now, "
                + "last_rerun_reason = :reason, last_rerun_by = :by, definitions_version = :version "
                + "WHERE month = :m", p);
        } else {
            jdbc.update("INSERT INTO metric_closes (month, closed_at, definitions_version, official) "
                + "VALUES (:m, :now, :version, :official)", p);
        }
        return true;
    }

    // ── Custom ranges ────────────────────────────────────────────────────────

    /** Every metric for a date range, computed live (not saved). */
    public List<Map<String, Object>> periodRows(LocalDate first, LocalDate last) {
        return jdbc.queryForList(sql("period_metrics.sql"), periodParams(first, last));
    }

    // ── Parameters ───────────────────────────────────────────────────────────

    /**
     * Bounds and windows for a period. Once launched, nothing before launch
     * day counts: the launch month starts on launch day, rolling windows start
     * no earlier than launch day, and the "period before" (growth accounting)
     * is empty for the launch month itself.
     */
    MapSqlParameterSource periodParams(LocalDate first, LocalDate last) {
        boolean official = !last.isBefore(getOfficialStart());
        if (official && first.isBefore(getOfficialStart())) {
            first = getOfficialStart();
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1;
        boolean wholeMonth = first.getDayOfMonth() == 1 && last.equals(YearMonth.from(first).atEndOfMonth());

        LocalDate prevFirst;
        LocalDate prevLast = first.minusDays(1);
        if (wholeMonth) {
            prevFirst = YearMonth.from(first).minusMonths(1).atDay(1);
        } else {
            prevFirst = first.minusDays(days);
        }
        if (official && prevFirst.isBefore(getOfficialStart())) {
            prevFirst = getOfficialStart();
        }
        if (prevFirst.isAfter(prevLast)) {     // nothing before: an empty range
            prevFirst = first;
        }

        return new MapSqlParameterSource()
            .addValue("first", first)
            .addValue("last", last)
            .addValue("mstart", dbStartOf(first))
            .addValue("mend", dbStartOf(last.plusDays(1)))
            .addValue("wstart", windowStart(last))
            .addValue("prev_first", prevFirst)
            .addValue("prev_last", prevLast)
            .addValue("dbtz", dbZoneName())
            .addValue("suspicious", suspiciousDailyPlays)
            .addValue("cohort_floor", official ? getOfficialStart() : PREVIEW_COHORT_FLOOR);
    }

    /** First day of the 30-day window ending on d; never before launch day once launched. */
    LocalDate windowStart(LocalDate d) {
        LocalDate w = d.minusDays(29);
        if (!d.isBefore(getOfficialStart()) && w.isBefore(getOfficialStart())) {
            return getOfficialStart();
        }
        return w;
    }

    /** Midnight of a New York date, in the database's clock. */
    LocalDateTime dbStartOf(LocalDate nyDate) {
        return nyDate.atStartOfDay(NEW_YORK).withZoneSameInstant(dbZone()).toLocalDateTime();
    }

    private ZoneId dbZone() {
        if (dbZoneOverride != null && !dbZoneOverride.isBlank()) {
            return ZoneId.of(dbZoneOverride.trim());
        }
        return ZoneId.systemDefault();
    }

    /** A zone name PostgreSQL understands, for AT TIME ZONE. */
    private String dbZoneName() {
        ZoneId zone = dbZone().normalized();
        if (zone instanceof ZoneOffset offset) {
            if (offset.getTotalSeconds() == 0) return "UTC";
            // Etc/GMT names are sign-inverted by convention: Etc/GMT+5 means UTC-5.
            int hours = -offset.getTotalSeconds() / 3600;
            return "Etc/GMT" + (hours >= 0 ? "+" + hours : String.valueOf(hours));
        }
        return zone.getId();
    }

    // ── SQL files ────────────────────────────────────────────────────────────

    /** Loads a file from resources/metrics, drops whole-line comments, fills {{ELIGIBLE_USERS}}. */
    String sql(String name) {
        return sqlCache.computeIfAbsent(name, n -> {
            String body = read(n);
            if (body.contains("{{ELIGIBLE_USERS}}")) {
                body = body.replace("{{ELIGIBLE_USERS}}", read("eligible_users.sql"));
            }
            return body;
        });
    }

    private static String read(String name) {
        try (InputStream in = new ClassPathResource("metrics/" + name).getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new IllegalStateException("Missing metrics SQL file: " + name, e);
        }
    }
}
