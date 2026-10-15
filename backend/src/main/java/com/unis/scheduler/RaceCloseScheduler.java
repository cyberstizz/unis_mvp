package com.unis.scheduler;

import com.unis.entity.CronExecution;
import com.unis.entity.VotingInterval;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.VotingIntervalRepository;
import com.unis.service.AwardService;
import com.unis.service.CronMonitorService;
import com.unis.service.RaceClockService;
import com.unis.service.TerritoryRankService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Closes every race at its own midnight.
 *
 * <p>Runs at one minute past every hour. Every US timezone changes day exactly
 * on the hour, so each race is decided within a minute of its period ending —
 * Harlem at 12:01 AM New York time, a Los Angeles race at 12:01 AM Pacific, and
 * the Unis-wide race once the day has ended for the last zone anyone on Unis
 * lives in. A run with nothing to close is a handful of small queries.
 *
 * <p>For each voting-enabled jurisdiction and each interval it asks the race
 * clock for the latest period that has ended for everyone in that race, and
 * compares it with the ledger (race_period_closures):
 * <ul>
 *   <li>Already recorded: nothing to do.</li>
 *   <li>Newer periods ended: each is decided, oldest first, and recorded in the
 *       same transaction as its awards. If the server was down, the missed
 *       periods are caught up here (up to {@value #MAX_CLOSES_PER_RACE_PER_RUN}
 *       per race per run; the rest follow next hour).</li>
 *   <li>Race never seen before (first deploy, or a jurisdiction just switched
 *       on): the latest ended period is recorded as a BASELINE without
 *       deciding anything, so opening a region never awards days from before
 *       it opened. Its first award is the first period that ends after that.</li>
 * </ul>
 *
 * <p>Runs are logged to cron_executions under the same job names the old
 * crons used (DAILY_AWARDS, WEEKLY_AWARDS, ...), only when a period was
 * actually closed, so the admin cron panel keeps working unchanged.
 */
@Component
public class RaceCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(RaceCloseScheduler.class);

    static final int MAX_CLOSES_PER_RACE_PER_RUN = 14;

    private static final Map<String, String> JOB_NAMES = Map.of(
        "Daily", "DAILY_AWARDS",
        "Weekly", "WEEKLY_AWARDS",
        "Monthly", "MONTHLY_AWARDS",
        "Quarterly", "QUARTERLY_AWARDS",
        "Midterm", "MIDTERM_AWARDS",
        "Annual", "ANNUAL_AWARDS"
    );

    private final AwardService awardService;
    private final TerritoryRankService territoryRankService;
    private final RaceClockService raceClock;
    private final JurisdictionRepository jurisdictionRepository;
    private final VotingIntervalRepository votingIntervalRepository;
    private final SongRepository songRepository;
    private final CronMonitorService cronMonitorService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public RaceCloseScheduler(AwardService awardService,
                              TerritoryRankService territoryRankService,
                              RaceClockService raceClock,
                              JurisdictionRepository jurisdictionRepository,
                              VotingIntervalRepository votingIntervalRepository,
                              SongRepository songRepository,
                              CronMonitorService cronMonitorService,
                              JdbcTemplate jdbc,
                              PlatformTransactionManager transactionManager) {
        this.awardService = awardService;
        this.territoryRankService = territoryRankService;
        this.raceClock = raceClock;
        this.jurisdictionRepository = jurisdictionRepository;
        this.votingIntervalRepository = votingIntervalRepository;
        this.songRepository = songRepository;
        this.cronMonitorService = cronMonitorService;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "0 1 * * * *", zone = "UTC")
    public void closeFinishedPeriods() {
        try {
            runOnce();
        } catch (Exception e) {
            log.error("[RaceClose] action=run status=fail err={}", e.getMessage(), e);
        }
    }

    /**
     * One pass over every race. Returns awards created per interval name.
     * Synchronized so a slow run can never overlap the next one.
     */
    public synchronized Map<String, Integer> runOnce() {
        long t0 = System.currentTimeMillis();
        List<UUID> races = jurisdictionRepository.findVotingEnabledJurisdictionIds();
        List<VotingInterval> intervals = orderedIntervals();

        Map<String, Integer> createdByInterval = new LinkedHashMap<>();
        Map<String, CronExecution> executions = new HashMap<>();
        Map<String, String> failures = new HashMap<>();
        Set<UUID> dayEnded = new LinkedHashSet<>();
        int closed = 0;
        int baselined = 0;

        for (VotingInterval interval : intervals) {
            String name = interval.getName();
            for (UUID race : races) {
                List<LocalDate> pending;
                try {
                    pending = pendingPeriodEnds(race, interval);
                } catch (Exception e) {
                    log.error("[RaceClose] action=plan status=fail race={} interval={} err={}",
                        race, name, e.getMessage(), e);
                    continue;
                }
                if (pending == null) {               // baseline recorded
                    baselined++;
                    if ("Daily".equals(name)) dayEnded.add(race);
                    continue;
                }
                for (LocalDate periodEnd : pending) {
                    CronExecution exec = executions.computeIfAbsent(name,
                        n -> cronMonitorService.startExecution(JOB_NAMES.getOrDefault(n, n.toUpperCase() + "_AWARDS")));
                    try {
                        int created = awardService.closeRacePeriod(periodEnd, interval.getIntervalId(), race);
                        createdByInterval.merge(name, created, Integer::sum);
                        closed++;
                        if ("Daily".equals(name)) dayEnded.add(race);
                        log.info("[RaceClose] action=close status=ok race={} interval={} periodEnd={} awards={}",
                            race, name, periodEnd, created);
                    } catch (Exception e) {
                        failures.put(name, e.getMessage());
                        log.error("[RaceClose] action=close status=fail race={} interval={} periodEnd={} err={}",
                            race, name, periodEnd, e.getMessage(), e);
                        break;   // keep this race's later periods for the next run, in order
                    }
                }
            }
        }

        executions.forEach((name, exec) -> {
            if (failures.containsKey(name)) {
                cronMonitorService.markFailed(exec, failures.get(name));
            } else {
                cronMonitorService.markSuccess(exec, createdByInterval.getOrDefault(name, 0));
            }
        });

        // Territory ranks follow each jurisdiction's own day.
        if (!dayEnded.isEmpty()) {
            try {
                territoryRankService.refreshFor(dayEnded);
            } catch (Exception e) {
                log.error("[RaceClose] action=territoryRanks status=fail err={}", e.getMessage(), e);
            }
        }

        // plays_today ("trending today") still resets on New York's day, as before.
        if (LocalTime.now(RaceClockService.DEFAULT_ZONE).getHour() == 0) {
            try {
                Integer reset = tx.execute(s -> songRepository.resetPlaysToday(LocalDate.now(RaceClockService.DEFAULT_ZONE)));
                log.info("[RaceClose] action=resetPlaysToday status=ok songs={}", reset);
            } catch (Exception e) {
                log.error("[RaceClose] action=resetPlaysToday status=fail err={}", e.getMessage(), e);
            }
        }

        log.info("[RaceClose] action=run status=ok races={} closed={} baselined={} failures={} durationMs={}",
            races.size(), closed, baselined, failures.size(), System.currentTimeMillis() - t0);
        return createdByInterval;
    }

    /**
     * Period ends this race still needs decided, oldest first. Returns null
     * when the race was just baselined, an empty list when it is up to date.
     */
    private List<LocalDate> pendingPeriodEnds(UUID race, VotingInterval interval) {
        String name = interval.getName();
        LocalDate latestClosed = raceClock.latestClosedPeriodEnd(race, name);
        LocalDate lastRecorded = jdbc.queryForObject(
            "SELECT MAX(period_end) FROM race_period_closures WHERE jurisdiction_id = ? AND interval_id = ?",
            LocalDate.class, race, interval.getIntervalId());

        if (lastRecorded == null) {
            jdbc.update(
                "INSERT INTO race_period_closures (jurisdiction_id, interval_id, period_end, kind) "
                    + "VALUES (?, ?, ?, 'BASELINE') ON CONFLICT DO NOTHING",
                race, interval.getIntervalId(), latestClosed);
            log.info("[RaceClose] action=baseline race={} interval={} periodEnd={}", race, name, latestClosed);
            return null;
        }
        if (!latestClosed.isAfter(lastRecorded)) {
            return List.of();
        }

        LinkedList<LocalDate> ends = new LinkedList<>();
        LocalDate end = latestClosed;
        int guard = 0;
        while (end.isAfter(lastRecorded) && guard++ < 1000) {
            ends.addFirst(end);
            end = RaceClockService.periodContaining(name, end)[0].minusDays(1);
        }
        if (ends.size() > MAX_CLOSES_PER_RACE_PER_RUN) {
            log.warn("[RaceClose] action=catchUp race={} interval={} behind={} closingThisRun={}",
                race, name, ends.size(), MAX_CLOSES_PER_RACE_PER_RUN);
            return new ArrayList<>(ends.subList(0, MAX_CLOSES_PER_RACE_PER_RUN));
        }
        return ends;
    }

    private List<VotingInterval> orderedIntervals() {
        List<VotingInterval> all = new ArrayList<>();
        for (VotingInterval vi : votingIntervalRepository.findAll()) {
            if (RaceClockService.INTERVAL_NAMES.contains(vi.getName())) all.add(vi);
        }
        all.sort(Comparator.comparingInt(vi -> RaceClockService.INTERVAL_NAMES.indexOf(vi.getName())));
        return all;
    }
}
