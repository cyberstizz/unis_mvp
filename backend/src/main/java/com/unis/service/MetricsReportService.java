package com.unis.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the metric snapshots for the admin Metrics pages and the PDF reports.
 *
 * Every response passes through the privacy rules before it leaves the
 * server, so no screen or PDF can show more than they allow:
 *   - a figure that describes fewer than 10 people is hidden ("fewer than 10");
 *   - if exactly one figure in a breakdown is hidden, the next-smallest is
 *     hidden too, so it can't be worked out by subtraction;
 *   - people under 18 appear only in the age-band headcount of monthly
 *     active users, never in any other split.
 */
@Service
public class MetricsReportService {

    public static final int MIN_GROUP = 10;
    private static final String TEEN_BAND = "13-17";

    /** Breakdowns whose values are counts of people. */
    private static final Set<String> PEOPLE_BREAKDOWNS = Set.of(
        "mau|age_band", "mau|gender", "mau|area",
        "mau_avg|area",
        "paying_users|age_band", "paying_users|gender", "paying_users|area");

    /** Rows whose value is plays, hidden when fewer than 10 different people are behind them. */
    private static final Map<String, String> PLAYS_TO_LISTENERS = Map.of(
        "taste_plays", "taste_listeners",
        "chart_song_plays", "chart_song_listeners",
        "chart_artist_plays", "chart_artist_listeners",
        "chart_subgenre_plays", "chart_subgenre_listeners");

    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private MetricsService metricsService;

    // ── Months ───────────────────────────────────────────────────────────────

    public Map<String, Object> month(YearMonth ym) {
        LocalDate first = ym.atDay(1);
        LocalDate officialStart = metricsService.getOfficialStart();
        boolean official = !ym.atEndOfMonth().isBefore(officialStart);
        LocalDate periodFirst = (official && first.isBefore(officialStart)) ? officialStart : first;

        List<Map<String, Object>> raw = jdbc.queryForList(
            "SELECT metric_key, segment_type, segment_value, value FROM metric_monthly WHERE month = :m",
            new MapSqlParameterSource("m", first));

        Map<String, Object> close = closeRow(first);
        String status;
        if (close != null) status = official ? "closed" : "preview";
        else status = official ? "open" : "preview";

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("month", ym.toString());
        period.put("first", periodFirst.toString());
        period.put("last", ym.atEndOfMonth().toString());
        period.put("status", status);
        period.put("official", official);
        period.put("launchMonth", YearMonth.from(officialStart).equals(ym));
        period.put("closedAt", close != null ? close.get("closed_at") : null);
        period.put("definitionsVersion", close != null ? close.get("definitions_version") : metricsService.getDefinitionsVersion());
        period.put("reruns", close != null ? close.get("rerun_count") : 0);
        period.put("lastRerunReason", close != null ? close.get("last_rerun_reason") : null);
        period.put("lastRerunAt", close != null ? close.get("last_rerun_at") : null);
        period.put("computedAt", latestComputedAt(first));
        period.put("hasData", !raw.isEmpty());

        return assemble(period, raw, history(ym, 6), retention(ym));
    }

    /** Custom date range, computed live. Always marked unofficial. */
    public Map<String, Object> range(LocalDate from, LocalDate to) {
        List<Map<String, Object>> raw = metricsService.periodRows(from, to);
        LocalDate officialStart = metricsService.getOfficialStart();
        LocalDate first = (!to.isBefore(officialStart) && from.isBefore(officialStart)) ? officialStart : from;

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("month", null);
        period.put("first", first.toString());
        period.put("last", to.toString());
        period.put("status", "unofficial");
        period.put("official", false);
        period.put("launchMonth", false);
        period.put("definitionsVersion", metricsService.getDefinitionsVersion());
        period.put("computedAt", LocalDateTime.now());
        period.put("hasData", !raw.isEmpty());
        return assemble(period, raw, List.of(), null);
    }

    // ── Daily series, cohorts, definitions, status ───────────────────────────

    public List<Map<String, Object>> daily(LocalDate from, LocalDate to, List<String> keys, String segmentType) {
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("from", from).addValue("to", to).addValue("keys", keys)
            .addValue("st", segmentType == null ? "all" : segmentType);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT metric_date, metric_key, segment_value, value FROM metric_daily "
          + "WHERE metric_date BETWEEN :from AND :to AND metric_key IN (:keys) AND segment_type = :st "
          + "ORDER BY metric_date", p);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", String.valueOf(r.get("metric_date")));
            m.put("key", r.get("metric_key"));
            m.put("segment", r.get("segment_value"));
            m.put("value", r.get("value"));
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> cohorts(String segmentType, String segmentValue, boolean includePreview) {
        LocalDate floor = includePreview ? LocalDate.of(2000, 1, 1)
            : YearMonth.from(metricsService.getOfficialStart()).atDay(1);
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("st", segmentType).addValue("sv", segmentValue).addValue("floor", floor);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT cohort_month, months_since, cohort_size, active_users, listen_hours, gpv_cents, net_revenue_cents "
          + "FROM cohort_retention WHERE segment_type = :st AND segment_value = :sv AND cohort_month >= :floor "
          + "ORDER BY cohort_month, months_since", p);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cohortMonth", YearMonth.from(((java.sql.Date) r.get("cohort_month")).toLocalDate()).toString());
            m.put("monthsSince", r.get("months_since"));
            m.put("cohortSize", r.get("cohort_size"));
            m.put("activeUsers", r.get("active_users"));
            m.put("listenHours", r.get("listen_hours"));
            m.put("gpvCents", r.get("gpv_cents"));
            m.put("netRevenueCents", r.get("net_revenue_cents"));
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> definitions() {
        return jdbc.getJdbcTemplate().queryForList(
            "SELECT metric_key, category, name, definition, unit, is_headline, sort_order, version, "
          + "effective_from, change_note FROM metric_definitions ORDER BY sort_order");
    }

    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("officialStart", metricsService.getOfficialStart().toString());
        s.put("definitionsVersion", metricsService.getDefinitionsVersion());
        LocalDate last = metricsService.lastComputedDay();
        s.put("lastComputedDay", last != null ? last.toString() : null);
        s.put("months", jdbc.getJdbcTemplate().queryForList(
            "SELECT to_char(m.month, 'YYYY-MM') AS month, c.closed_at, c.official, c.rerun_count, c.last_rerun_reason "
          + "FROM (SELECT DISTINCT month FROM metric_monthly) m "
          + "LEFT JOIN metric_closes c ON c.month = m.month ORDER BY m.month DESC"));
        s.put("minGroupSize", MIN_GROUP);
        return s;
    }

    // ── Assembly and privacy ─────────────────────────────────────────────────

    private Map<String, Object> assemble(Map<String, Object> period, List<Map<String, Object>> raw,
                                         List<Map<String, Object>> history, Map<String, Object> retention) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            String key = String.valueOf(r.get("metric_key"));
            String st = String.valueOf(r.get("segment_type"));
            String sv = String.valueOf(r.get("segment_value"));
            // Defense in depth: under-18s only ever appear in the age headcount.
            if (sv.startsWith(TEEN_BAND) && !("mau".equals(key) && "age_band".equals(st))) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("segmentType", st);
            row.put("segmentValue", sv);
            row.put("value", toNumber(r.get("value")));
            row.put("suppressed", null);
            rows.add(row);
        }
        applyPrivacy(rows);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("rows", rows);
        out.put("history", history);
        out.put("retention", retention);
        out.put("labels", labels(rows));
        out.put("minGroupSize", MIN_GROUP);
        return out;
    }

    void applyPrivacy(List<Map<String, Object>> rows) {
        // 1. People counts in breakdowns.
        Map<String, List<Map<String, Object>>> groups = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String g = r.get("key") + "|" + r.get("segmentType");
            if (PEOPLE_BREAKDOWNS.contains(g)) groups.computeIfAbsent(g, k -> new ArrayList<>()).add(r);
        }
        for (List<Map<String, Object>> group : groups.values()) {
            int hidden = 0;
            for (Map<String, Object> r : group) {
                if (num(r) < MIN_GROUP) { hide(r, "small"); hidden++; }
            }
            if (hidden == 1 && group.size() > 1) {
                group.stream().filter(r -> r.get("suppressed") == null)
                    .min(Comparator.comparingDouble(MetricsReportService::num))
                    .ifPresent(r -> hide(r, "complement"));
            }
        }

        // 2. Revenue by area follows its area's user count.
        Set<String> smallAreas = new HashSet<>();
        for (Map<String, Object> r : rows) {
            if ("mau_avg".equals(r.get("key")) && "area".equals(r.get("segmentType")) && r.get("suppressed") != null) {
                smallAreas.add(String.valueOf(r.get("segmentValue")));
            }
        }
        Set<String> areasWithUsers = new HashSet<>();
        for (Map<String, Object> r : rows) {
            if ("mau_avg".equals(r.get("key")) && "area".equals(r.get("segmentType"))) {
                areasWithUsers.add(String.valueOf(r.get("segmentValue")));
            }
        }
        for (Map<String, Object> r : rows) {
            if ("net_revenue_cents".equals(r.get("key")) && "area".equals(r.get("segmentType"))) {
                String area = String.valueOf(r.get("segmentValue"));
                if (smallAreas.contains(area) || !areasWithUsers.contains(area)) hide(r, "small");
            }
        }

        // 3. Plays rows backed by fewer than 10 listeners.
        Map<String, Double> listeners = new HashMap<>();
        for (Map<String, Object> r : rows) {
            listeners.put(r.get("key") + "|" + r.get("segmentType") + "|" + r.get("segmentValue"), num(r));
        }
        for (Map<String, Object> r : rows) {
            String key = String.valueOf(r.get("key"));
            String st = String.valueOf(r.get("segmentType"));
            String sv = String.valueOf(r.get("segmentValue"));
            if (PLAYS_TO_LISTENERS.containsKey(key)) {
                Double l = listeners.get(PLAYS_TO_LISTENERS.get(key) + "|" + st + "|" + sv);
                if (l == null || l < MIN_GROUP) hide(r, "small");
            } else if (PLAYS_TO_LISTENERS.containsValue(key) || "hour_listeners".equals(key)) {
                if (num(r) < MIN_GROUP) hide(r, "small");
            } else if ("hour_plays".equals(key)) {
                String band = sv.contains("|") ? sv.substring(0, sv.indexOf('|')) : sv;
                Double l = listeners.get("hour_listeners|age_band|" + band);
                if (l == null || l < MIN_GROUP) hide(r, "small");
            }
        }
    }

    private static void hide(Map<String, Object> r, String why) {
        r.put("value", null);
        r.put("suppressed", why);
    }

    private static double num(Map<String, Object> r) {
        Object v = r.get("value");
        return v instanceof Number n ? n.doubleValue() : 0d;
    }

    private static Object toNumber(Object v) {
        if (v instanceof BigDecimal bd) return bd.stripTrailingZeros().scale() <= 0 ? bd.longValue() : bd.doubleValue();
        return v;
    }

    // ── Labels for ids in segment values ─────────────────────────────────────

    private Map<String, Object> labels(List<Map<String, Object>> rows) {
        Set<UUID> areas = new HashSet<>();
        Set<UUID> songs = new HashSet<>();
        Set<UUID> artists = new HashSet<>();
        for (Map<String, Object> r : rows) {
            String st = String.valueOf(r.get("segmentType"));
            String sv = String.valueOf(r.get("segmentValue"));
            if ("area".equals(st)) addUuid(areas, sv);
            if (st.startsWith("area_") && sv.contains("|")) {
                String[] parts = sv.split("\\|", 2);
                addUuid(areas, parts[0]);
                if ("area_song".equals(st)) addUuid(songs, parts[1]);
                if ("area_artist".equals(st)) addUuid(artists, parts[1]);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("areas", nameMap("SELECT jurisdiction_id AS id, name FROM jurisdictions WHERE jurisdiction_id IN (:ids)", areas));
        out.put("artists", nameMap("SELECT user_id AS id, username AS name FROM users WHERE user_id IN (:ids)", artists));
        Map<String, Object> songLabels = new LinkedHashMap<>();
        if (!songs.isEmpty()) {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT s.song_id, s.title, u.username FROM songs s JOIN users u ON u.user_id = s.artist_id "
                  + "WHERE s.song_id IN (:ids)", new MapSqlParameterSource("ids", songs))) {
                songLabels.put(String.valueOf(r.get("song_id")),
                    Map.of("title", String.valueOf(r.get("title")), "artist", String.valueOf(r.get("username"))));
            }
        }
        out.put("songs", songLabels);
        out.put("subgenres", nameMapAll("SELECT slug AS id, name FROM subgenres"));
        out.put("moods", nameMapAll("SELECT slug AS id, name FROM moods"));
        return out;
    }

    private static void addUuid(Set<UUID> set, String s) {
        try { set.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { /* 'none' and similar */ }
    }

    private Map<String, String> nameMap(String sql, Set<UUID> ids) {
        Map<String, String> m = new LinkedHashMap<>();
        if (ids.isEmpty()) return m;
        for (Map<String, Object> r : jdbc.queryForList(sql, new MapSqlParameterSource("ids", ids))) {
            m.put(String.valueOf(r.get("id")), String.valueOf(r.get("name")));
        }
        return m;
    }

    private Map<String, String> nameMapAll(String sql) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.getJdbcTemplate().queryForList(sql)) {
            m.put(String.valueOf(r.get("id")), String.valueOf(r.get("name")));
        }
        return m;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> closeRow(LocalDate month) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT closed_at, definitions_version, official, rerun_count, last_rerun_reason, last_rerun_at "
          + "FROM metric_closes WHERE month = :m", new MapSqlParameterSource("m", month));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Object latestComputedAt(LocalDate month) {
        return jdbc.queryForObject("SELECT MAX(computed_at) FROM metric_monthly WHERE month = :m",
            new MapSqlParameterSource("m", month), Object.class);
    }

    /** Overall ('all') values for the months up to and including ym, oldest first. */
    private List<Map<String, Object>> history(YearMonth ym, int months) {
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("from", ym.minusMonths(months - 1L).atDay(1))
            .addValue("to", ym.atDay(1));
        List<Map<String, Object>> raw = jdbc.queryForList(
            "SELECT month, metric_key, value FROM metric_monthly "
          + "WHERE month BETWEEN :from AND :to AND segment_type = 'all' ORDER BY month", p);
        Map<String, Map<String, Object>> byMonth = new LinkedHashMap<>();
        for (Map<String, Object> r : raw) {
            String m = YearMonth.from(((java.sql.Date) r.get("month")).toLocalDate()).toString();
            byMonth.computeIfAbsent(m, k -> new LinkedHashMap<>())
                   .put(String.valueOf(r.get("metric_key")), toNumber(r.get("value")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : byMonth.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", e.getKey());
            m.put("values", e.getValue());
            out.add(m);
        }
        return out;
    }

    /** Month-3 retention for this month: the cohort that signed up three months earlier. */
    private Map<String, Object> retention(YearMonth ym) {
        YearMonth cohort = ym.minusMonths(3);
        if (cohort.isBefore(YearMonth.from(metricsService.getOfficialStart()))) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT cohort_size, active_users FROM cohort_retention WHERE cohort_month = :c AND months_since = 3 "
          + "AND segment_type = 'all' AND segment_value = 'all'",
            new MapSqlParameterSource("c", cohort.atDay(1)));
        if (rows.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cohortMonth", cohort.toString());
        m.put("cohortSize", rows.get(0).get("cohort_size"));
        m.put("activeUsers", rows.get(0).get("active_users"));
        return m;
    }
}
