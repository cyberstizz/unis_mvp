package com.unis.service;

import com.unis.entity.Award;
import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.entity.Genre;
import com.unis.entity.CronExecution;
import com.unis.entity.Jurisdiction;
import com.unis.entity.VotingInterval;
import com.unis.service.CronMonitorService;
import com.unis.repository.AwardRepository;
import com.unis.repository.VoteRepository;
import com.unis.repository.VotingIntervalRepository;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.GenreRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.UserRepository;
import com.unis.dto.LeaderboardEntryDto;
import com.unis.dto.PeriodLeaderboardDto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AwardService {
    @Autowired
    private AwardRepository awardRepository;

    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private VotingIntervalRepository votingIntervalRepository;

    @Autowired
    private JurisdictionRepository jurisdictionRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ScoreUpdateService scoreUpdateService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private RankingService rankingService;

    @Autowired
    private RaceClockService raceClock;

    private final CronMonitorService cronMonitorService;

    private static final java.time.ZoneId UNIS_ZONE = java.time.ZoneId.of("America/New_York");
    
    @Lazy
    @Autowired
    private AwardService self;

    // unis.auto-populate-awards is retired: no read path creates awards any
    // more. Award rows come only from RaceCloseScheduler, after a period has
    // ended for everyone in the race.

    // Constructor injection for CronMonitorService
    @Autowired
    public AwardService(CronMonitorService cronMonitorService) {
        this.cronMonitorService = cronMonitorService;
    }


    private static final Logger log = LoggerFactory.getLogger(AwardService.class);

    // =========================================================================
    // AWARD POINT VALUES - Points added to winner's score
    // =========================================================================
    private static final Map<String, Integer> AWARD_POINTS = Map.of(
        "Daily", 50,
        "Weekly", 100,
        "Monthly", 250,
        "Quarterly", 500,
        "Midterm", 2500,
        "Annual", 5000
    );

    // =========================================================================
    // RANKING FORMULA — vote weights, play/like weights and the tiebreaker
    // order are defined once, in RankingService. Award winners, live
    // standings, nominees and territory rank all use that one definition.
    // =========================================================================

    // =========================================================================
    // PUBLIC API METHODS
    // =========================================================================

    @Transactional(readOnly = true)
    @Cacheable(value = "leaderboards", key = "'leaderboard-' + #type + '-' + #intervalId + '-' + #jurisdictionId")
    public List<Award> getLeaderboards(String type, UUID intervalId, UUID jurisdictionId) {
        if (intervalId == null) {
            Optional<VotingInterval> dailyInterval = votingIntervalRepository.findByName("Daily");
            if (dailyInterval.isPresent()) {
                intervalId = dailyInterval.get().getIntervalId();
            } else {
                return new ArrayList<>();
            }
        }
        
        LocalDate start = LocalDate.now(UNIS_ZONE).minusDays(30);
        LocalDate end = LocalDate.now(UNIS_ZONE);
        List<Award> awards = awardRepository.findTopByPeriod(jurisdictionId, intervalId, start, end);

        // PURE READ. This endpoint used to compute and SAVE awards when it found
        // none — for a period still running, that froze a winner from partial
        // data. Missing rows now just fall through to the unsaved display list.
        if (awards.isEmpty()) {
            awards = createFallbackAwards(type, jurisdictionId, intervalId, end);
        }
        
        return populateAwardEntities(awards);
    }

    @Transactional(readOnly = true)
    public List<Award> getPastAwards(String type, LocalDate startDate, LocalDate endDate,
                                    UUID jurisdictionId, UUID genreId, UUID intervalId) {
        log.info("getPastAwards: type={}, range={}..{}, jurisdiction={}, genre={}, interval={}",
                type, startDate, endDate, jurisdictionId, genreId, intervalId);

    if (intervalId == null) {
        intervalId = determineIntervalFromDateRange(startDate, endDate)
                .map(VotingInterval::getIntervalId).orElse(null);
    }
    if (intervalId == null) {
        log.warn("getPastAwards: could not resolve interval for range {}..{}", startDate, endDate);
        return new ArrayList<>();
    }

    // PURE READ. Award rows are produced exclusively by the scheduled crons.
    // Computing/fabricating on read stamped award_date by page-view time and
    // scattered off-cadence rows across jurisdictions (the uptown 07-16 anomaly).
    // If the slot is empty, it's empty — the client renders an honest empty state.
    List<Award> awards = awardRepository.findByFilters(
            type, jurisdictionId, genreId, intervalId, startDate, endDate);

    if (awards.isEmpty()) {
        log.info("getPastAwards: no {} award for jurisdiction={} genre={} interval={} in {}..{} "
               + "(no eligible candidate for this category, or cron has not run this slot)",
                 type, jurisdictionId, genreId, intervalId, startDate, endDate);
        return new ArrayList<>();
    }
    return populateAwardEntities(awards);
}


    public List<Award> getPastAwards(String type, LocalDate startDate, LocalDate endDate, 
                                      UUID jurisdictionId, UUID genreId) {
        return getPastAwards(type, startDate, endDate, jurisdictionId, genreId, null);
    }


    /**
 * Get the full ranked leaderboard for a period, plus the saved winner Award.
 *
 * PURE READ. Viewing a period never decides it. Before this change a request
 * for a period still running (a bot, a stale screen, a hand-typed URL) saved
 * that moment's leader as the winner, paid them the award points, and the real
 * close job then skipped the slot because a winner already existed.
 *
 * status tells the client what it is looking at:
 *   OPEN      the period is still running somewhere in this race — standings
 *             only, no winner; closesAt / stillOpenIn say when and where
 *   FINAL     decided — the winner is pinned first
 *   PENDING   ended in the last two hours, results posting on the hourly job
 *   NO_AWARD  ended long ago and no award was recorded for this category
 */
@Transactional(readOnly = true)
public PeriodLeaderboardDto getPeriodLeaderboard(String type, LocalDate startDate, LocalDate endDate,
                                                  UUID jurisdictionId, UUID genreId, UUID intervalId,
                                                  int limit) {
    log.info("getPeriodLeaderboard: type={}, range={}..{}, jurisdiction={}, genre={}, interval={}",
                type, startDate, endDate, jurisdictionId, genreId, intervalId);

    java.time.Instant closesAt = raceClock.closesAt(jurisdictionId, endDate);
    boolean closed = !raceClock.now().isBefore(closesAt);

    // 1. The saved winner, if the close job has decided this period.
    List<Award> awards = closed
        ? awardRepository.findByFilters(type, jurisdictionId, genreId, intervalId, startDate, endDate)
        : new ArrayList<>();
    Award winner = awards.isEmpty() ? null : awards.get(0);
    if (winner != null) {
        populateAwardEntities(awards);
    }

    // 2. The ranked field, from the same formula the close job decides with.
    List<CandidateResult> candidates = getRankedCandidates(
        type, jurisdictionId, genreId, startDate, endDate, Math.max(limit, 1));

    // 3. A decided period always lists its winner first. The award was decided
    //    on the data at the close; a voter leaving since then can nudge the
    //    live numbers, and the winner card and the #1 row must never disagree.
    if (winner != null) {
        candidates = pinWinnerFirst(candidates, winner);
    }

    String status;
    if (!closed) {
        status = "OPEN";
    } else if (winner != null) {
        status = "FINAL";
    } else if (raceClock.now().isBefore(closesAt.plus(java.time.Duration.ofHours(2)))) {
        status = "PENDING";
    } else {
        status = "NO_AWARD";
    }

    // 4. Build response. Only a recorded award marks a winner.
    int totalVotes = candidates.stream().mapToInt(c -> c.rawVoteCount).sum();
    UUID winnerId = winner != null ? winner.getTargetId() : null;

    List<LeaderboardEntryDto> leaderboard = new ArrayList<>();
    int rank = 1;
    for (CandidateResult c : candidates.stream().limit(limit).collect(Collectors.toList())) {
        boolean isWinner = c.targetId.equals(winnerId);
        leaderboard.add(hydrateLeaderboardEntry(c, type, rank++, isWinner, winner));
    }

    List<String> stillOpenIn = closed ? List.of()
        : raceClock.zonesStillOpen(jurisdictionId, endDate).stream()
            .map(java.time.ZoneId::getId).collect(Collectors.toList());

    return PeriodLeaderboardDto.builder()
        .winner(winner)
        .leaderboard(leaderboard)
        .totalVotes(totalVotes)
        .status(status)
        .closesAt(closesAt.toString())
        .stillOpenIn(stillOpenIn)
        .build();
}

/**
 * Hydrate a CandidateResult into a leaderboard entry by pulling
 * title/artist/artwork from the song or user repository.
 * The winning entry also receives the Award's determination metadata.
 */
private LeaderboardEntryDto hydrateLeaderboardEntry(CandidateResult c, String type, int rank,
                                                     boolean isWinner, Award winnerAward) {
    String title = "";
    String artist = "";
    String artwork = null;
    String fileUrl = null;
    UUID artistId = null;

    if ("song".equals(type)) {
        Song song = songRepository.findById(c.targetId).orElse(null);
        if (song != null) {
            title = song.getTitle();
            artist = song.getArtist() != null ? song.getArtist().getUsername() : "Unknown Artist";
            artwork = song.getArtworkUrl();
            fileUrl = song.getFileUrl();
            artistId = song.getArtist() != null ? song.getArtist().getUserId() : null;

        }
    } else {
        User user = userRepository.findById(c.targetId).orElse(null);
        if (user != null) {
            title = user.getUsername();
            artist = user.getUsername();
            artwork = user.getPhotoUrl();
        }
    }

    LeaderboardEntryDto.LeaderboardEntryDtoBuilder builder = LeaderboardEntryDto.builder()
        .rank(rank)
        .targetId(c.targetId)
        .targetType(type)
        .title(title)
        .artist(artist)
        .artwork(artwork)
        .fileUrl(fileUrl)
        .artistId(artistId)
        .votes((long) c.rawVoteCount)
        .weightedPoints(c.weightedPoints)
        .engagementPoints(c.engagementPoints)
        .totalPoints(c.totalPoints)
        .playsCount(c.playsCount)
        .likesCount(c.likesCount)
        .isWinner(isWinner);

    // Determination metadata only meaningful on the winning entry
    if (isWinner && winnerAward != null) {
        builder.determinationMethod(winnerAward.getDeterminationMethod());
        builder.tiedCandidatesCount(winnerAward.getTiedCandidatesCount());
    }

    return builder.build();
}

    // =========================================================================
    // CLOSING A PERIOD
    // =========================================================================

    /**
     * Decide every award for one race's period and record it in the ledger,
     * in one transaction — either both land or neither does. Called by
     * RaceCloseScheduler once the period has ended for everyone in the race.
     *
     * @return how many award rows were created
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    @CacheEvict(value = {"awards", "leaderboards", "nominees"}, allEntries = true)
    public int closeRacePeriod(LocalDate periodEnd, UUID intervalId, UUID jurisdictionId) {
        int created = computeAwardsInternal(periodEnd, intervalId, jurisdictionId, null);
        entityManager.createNativeQuery(
                "INSERT INTO race_period_closures "
                + "(jurisdiction_id, interval_id, period_end, kind, awards_created) "
                + "VALUES (:jurisdictionId, :intervalId, :periodEnd, 'CLOSED', :created) "
                + "ON CONFLICT (jurisdiction_id, interval_id, period_end) DO NOTHING")
            .setParameter("jurisdictionId", jurisdictionId)
            .setParameter("intervalId", intervalId)
            .setParameter("periodEnd", periodEnd)
            .setParameter("created", created)
            .executeUpdate();
        return created;
    }

    @Transactional(readOnly = false)
    @CacheEvict(value = {"awards", "leaderboards", "nominees"}, allEntries = true)
    public void computeAwardsForDate(LocalDate awardDate, UUID intervalId, UUID jurisdictionId, UUID genreId) {
        computeAwardsInternal(awardDate, intervalId, jurisdictionId, genreId);
    }

    /**
     * @return how many award rows were created
     */
    private int computeAwardsInternal(LocalDate awardDate, UUID intervalId, UUID jurisdictionId, UUID genreId) {
        List<UUID> jurisdictions;
        if (jurisdictionId != null) {
            Jurisdiction jur = jurisdictionRepository.findById(jurisdictionId).orElse(null);
            if (jur == null || !Boolean.TRUE.equals(jur.getVotingEnabled())) {
                log.info("[Awards] skip jurisdiction={} reason=voting_not_enabled", jurisdictionId);
                return 0;
            }
            jurisdictions = List.of(jurisdictionId);
        } else {
            jurisdictions = jurisdictionRepository.findVotingEnabledJurisdictionIds();
        }

        List<UUID> genres = genreId != null ? List.of(genreId) : genreRepository.findAllGenreIds();

        VotingInterval interval = votingIntervalRepository.findById(intervalId).orElse(null);
        if (interval == null) {
            log.warn("[Awards] skip reason=interval_not_found interval={}", intervalId);
            return 0;
        }

        // The period's own window. A daily close for Oct 15 is Oct 15; a
        // weekly close for Sunday Oct 18 is Monday Oct 12 – Sunday Oct 18.
        LocalDate[] period = RaceClockService.periodContaining(interval.getName(), awardDate);
        LocalDate startDate = period[0];
        LocalDate endDate = period[1];

        int awardsCreated = 0;

        for (UUID jurId : jurisdictions) {
            // Every write path goes through here, so this is the one guard that
            // keeps a winner from ever being decided while anyone in the race is
            // still in that period.
            if (!raceClock.isClosed(jurId, endDate)) {
                log.warn("[Awards] refused jurisdiction={} interval={} period={}..{} reason=period_still_open closesAt={}",
                    jurId, interval.getName(), startDate, endDate, raceClock.closesAt(jurId, endDate));
                continue;
            }
            for (UUID genId : genres) {
                if (computeSingleWinnerAward("song", jurId, genId, intervalId, startDate, endDate, interval)) {
                    awardsCreated++;
                }
                if (computeSingleWinnerAward("artist", jurId, genId, intervalId, startDate, endDate, interval)) {
                    awardsCreated++;
                }
            }
        }

        log.info("[Awards] computed interval={} period={}..{} jurisdictions={} created={}",
            interval.getName(), startDate, endDate, jurisdictions.size(), awardsCreated);
        return awardsCreated;
    }

    /**
     * Compute a SINGLE winner for one category/jurisdiction/genre/interval/date.
     * Uses the full weighted voting + tiebreaker cascade.
     */
    private boolean computeSingleWinnerAward(String targetType, UUID jurisdictionId, UUID genreId,
                                              UUID intervalId, LocalDate startDate, LocalDate awardDate,
                                              VotingInterval interval) {
        
        if (awardRepository.existsAwardForCategory(targetType, jurisdictionId, genreId, intervalId, awardDate)) {
            System.out.println("Award already exists for " + targetType + " in jurisdiction " + jurisdictionId + " on " + awardDate);
            return false;
        }

        // ONE ranked field. Every eligible candidate is scored on votes AND
        // engagement in the same query, so an artist with plays but no votes is
        // a competitor rather than a fallback. Empty means the category has no
        // eligible artist/song at all, which is the only no-award case left.
        List<CandidateResult> candidates = getRankedCandidates(
            targetType, jurisdictionId, genreId, startDate, awardDate
        );

        if (candidates.isEmpty()) {
            System.out.println("No eligible " + targetType + "s in jurisdiction " + jurisdictionId
                             + " / genre " + genreId + " — no award for this category");
            return false;
        }

        // Determine winner using tiebreaker cascade
        WinnerResult winner = determineWinner(candidates);

        if (winner == null) {
            System.out.println("Could not determine winner for " + targetType);
            return false;
        }

        int awardPoints = AWARD_POINTS.getOrDefault(interval.getName(), 50);

        Award award = Award.builder()
            .targetType(targetType)
            .targetId(winner.targetId)
            .genre(genreRepository.findById(genreId).orElse(null))
            .jurisdiction(jurisdictionRepository.findById(jurisdictionId).orElse(null))
            .interval(interval)
            .awardDate(awardDate)
            .votesCount(winner.rawVoteCount)
            .weightedPoints(winner.weightedPoints)
            .engagementPoints(winner.engagementPoints)
            .totalPoints(winner.totalPoints)
            .playsCount(winner.playsCount)
            .likesCount(winner.likesCount)
            .engagementScore(winner.score)
            .weight(awardPoints)
            .determinationMethod(winner.determinationMethod)
            .winnerSeniority(winner.seniority)
            .tiedCandidatesCount(winner.tiedCandidatesCount)
            .build();

        Award savedAward = awardRepository.save(award);
        awardRepository.flush();
        
        System.out.println("✓ Award SAVED with ID: " + savedAward.getAwardId() + " for " + targetType + 
                          " winner in jurisdiction " + jurisdictionId + 
                          " with " + winner.totalPoints + " total points (" +
                          winner.weightedPoints + " vote + " + winner.engagementPoints + " engagement)" +
                          " (" + winner.rawVoteCount + " votes, " + winner.playsCount + " plays, " + winner.likesCount + " likes)" +
                          " (determined by " + winner.determinationMethod + ")");
        
        scoreUpdateService.onAward(winner.targetId, awardPoints);
        
        return true;
    }

    // =========================================================================
    // RANKED CANDIDATES — the shared formula in RankingService
    // =========================================================================

    private List<CandidateResult> getRankedCandidates(String targetType, UUID jurisdictionId,
                                                       UUID genreId, LocalDate startDate,
                                                       LocalDate endDate) {
        return getRankedCandidates(targetType, jurisdictionId, genreId, startDate, endDate, 50);
    }

    private List<CandidateResult> getRankedCandidates(String targetType, UUID jurisdictionId,
                                                       UUID genreId, LocalDate startDate,
                                                       LocalDate endDate, int limit) {
        List<RankingService.Candidate> ranked =
            rankingService.rank(targetType, jurisdictionId, genreId, startDate, endDate, limit, 0);

        List<CandidateResult> candidates = new ArrayList<>(ranked.size());
        for (RankingService.Candidate r : ranked) {
            CandidateResult c = new CandidateResult();
            c.targetId = r.targetId();
            c.rawVoteCount = r.rawVoteCount();
            c.weightedPoints = r.weightedPoints();
            c.engagementPoints = r.engagementPoints();
            c.totalPoints = r.totalPoints();
            c.playsCount = r.playsCount();
            c.likesCount = r.likesCount();
            c.score = r.score();
            c.seniority = r.seniority();
            candidates.add(c);
        }
        return candidates;
    }

    // =========================================================================
    // WINNER DETERMINATION WITH FULL TIEBREAKER CASCADE
    // =========================================================================

    /**
     * Determine the winner from the ranked list.
     *
     * The list arrives already sorted by the SQL, so candidates.get(0) is the
     * winner. This method's real job is to record HOW they won, for the audit
     * trail and the Milestones badge.
     *
     * Order: total points → vote points → plays → likes → score → seniority.
     *
     * determinationMethod values:
     *  - "VOTES"       clear win on total, votes were the larger half of it
     *  - "ENGAGEMENT"  clear win on total, plays/likes were the larger half
     *  - "VOTE_POINTS" tied on total, taken by the candidate with more vote points
     *  - "PLAYS" / "LIKES" / "SCORE" / "SENIORITY"  further tiebreakers
     *  - "NO_ACTIVITY" nothing happened in this category at all; the winner is
     *                  whoever leads on lifetime score, then seniority
     *
     * "WEIGHTED_VOTES" and "FALLBACK" are retired but still exist on historical
     * rows, so the frontend keeps rendering them.
     */
    private WinnerResult determineWinner(List<CandidateResult> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }

        CandidateResult top = candidates.get(0);

        WinnerResult winner = new WinnerResult();
        winner.targetId         = top.targetId;
        winner.rawVoteCount     = top.rawVoteCount;
        winner.weightedPoints   = top.weightedPoints;
        winner.engagementPoints = top.engagementPoints;
        winner.totalPoints      = top.totalPoints;
        winner.playsCount       = top.playsCount;
        winner.likesCount       = top.likesCount;
        winner.score            = top.score;
        winner.seniority        = top.seniority;

        int tiedOnTotal = 0, tiedOnVotePoints = 0, tiedOnPlays = 0, tiedOnLikes = 0, tiedOnScore = 0;

        for (CandidateResult c : candidates) {
            if (c.totalPoints == top.totalPoints) {
                tiedOnTotal++;
                if (c.weightedPoints == top.weightedPoints) {
                    tiedOnVotePoints++;
                    if (c.playsCount == top.playsCount) {
                        tiedOnPlays++;
                        if (c.likesCount == top.likesCount) {
                            tiedOnLikes++;
                            if (c.score == top.score) {
                                tiedOnScore++;
                            }
                        }
                    }
                }
            }
        }

        if (top.totalPoints == 0) {
            // Nothing was voted, played or liked in this category during the
            // period. Someone is still crowned — an empty jurisdiction should
            // not show an empty page — but it is labelled honestly.
            winner.determinationMethod = "NO_ACTIVITY";
            winner.tiedCandidatesCount = tiedOnTotal > 1 ? tiedOnTotal : 0;
        } else if (tiedOnTotal == 1) {
            winner.determinationMethod =
                    top.weightedPoints >= top.engagementPoints ? "VOTES" : "ENGAGEMENT";
            winner.tiedCandidatesCount = 0;
        } else if (tiedOnVotePoints == 1) {
            winner.determinationMethod = "VOTE_POINTS";
            winner.tiedCandidatesCount = tiedOnTotal;
        } else if (tiedOnPlays == 1) {
            winner.determinationMethod = "PLAYS";
            winner.tiedCandidatesCount = tiedOnVotePoints;
        } else if (tiedOnLikes == 1) {
            winner.determinationMethod = "LIKES";
            winner.tiedCandidatesCount = tiedOnPlays;
        } else if (tiedOnScore == 1) {
            winner.determinationMethod = "SCORE";
            winner.tiedCandidatesCount = tiedOnLikes;
        } else {
            winner.determinationMethod = "SENIORITY";
            winner.tiedCandidatesCount = tiedOnScore;
        }

        System.out.println("Winner determination: " + winner.determinationMethod +
                          " (total " + winner.totalPoints + ", tied candidates: " +
                          winner.tiedCandidatesCount + ")");

        return winner;
    }

    // =========================================================================
    // JURISDICTION HIERARCHY HELPERS
    // =========================================================================

    @SuppressWarnings("unchecked")
    private List<UUID> getJurisdictionAndAllChildren(UUID jurisdictionId) {
        String sql = """
            WITH RECURSIVE jurisdiction_tree AS (
                SELECT jurisdiction_id 
                FROM jurisdictions 
                WHERE jurisdiction_id = :jurisdictionId
                
                UNION ALL
                
                SELECT j.jurisdiction_id 
                FROM jurisdictions j
                INNER JOIN jurisdiction_tree jt ON j.parent_jurisdiction_id = jt.jurisdiction_id
            )
            SELECT jurisdiction_id FROM jurisdiction_tree
        """;
        
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("jurisdictionId", jurisdictionId);
        
        return query.getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<UUID> getJurisdictionAncestors(UUID jurisdictionId) {
        String sql = """
            WITH RECURSIVE ancestor_tree AS (
                SELECT parent_jurisdiction_id 
                FROM jurisdictions 
                WHERE jurisdiction_id = :jurisdictionId
                AND parent_jurisdiction_id IS NOT NULL
                
                UNION ALL
                
                SELECT j.parent_jurisdiction_id 
                FROM jurisdictions j
                INNER JOIN ancestor_tree at ON j.jurisdiction_id = at.parent_jurisdiction_id
                WHERE j.parent_jurisdiction_id IS NOT NULL
            )
            SELECT parent_jurisdiction_id FROM ancestor_tree
        """;
        
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("jurisdictionId", jurisdictionId);
        
        return query.getResultList();
    }

    // =========================================================================
    // SCHEDULED CLOSING lives in scheduler/RaceCloseScheduler: one job at one
    // minute past every hour closes each race at its own midnight. It replaced
    // six crons that all fired on Eastern time.
    // =========================================================================

    // =========================================================================
    // MANUAL COMPUTATION
    // =========================================================================

    @Transactional(readOnly = false)
    @CacheEvict(value = {"awards", "leaderboards"}, allEntries = true)
    public void computeForInterval(UUID intervalId, UUID jurisdictionId, UUID genreId, LocalDate cronDate) {
        computeAwardsInternal(cronDate, intervalId, jurisdictionId, genreId);
    }

    @Transactional(readOnly = false)
    public void computeDailyAwardsForDate(LocalDate cronDate) {
        Optional<VotingInterval> dailyInterval = votingIntervalRepository.findByName("Daily");
        if (dailyInterval.isEmpty()) return;
        computeAwardsInternal(cronDate, dailyInterval.get().getIntervalId(), null, null);
    }

    @Transactional(readOnly = false)
    @CacheEvict(value = {"awards", "leaderboards"}, allEntries = true)
    public void recomputeAllHistoricalAwards() {
        System.out.println("=== RECOMPUTING ALL HISTORICAL AWARDS ===");
        
        String sql = "SELECT DISTINCT vote_date FROM votes ORDER BY vote_date";
        Query query = entityManager.createNativeQuery(sql);
        
        @SuppressWarnings("unchecked")
        List<java.sql.Date> voteDates = query.getResultList();
        
        Optional<VotingInterval> dailyInterval = votingIntervalRepository.findByName("Daily");
        if (dailyInterval.isEmpty()) {
            System.out.println("ERROR: Daily interval not found!");
            return;
        }

        for (java.sql.Date sqlDate : voteDates) {
            LocalDate date = sqlDate.toLocalDate();
            System.out.println("Processing votes for " + date);
            computeAwardsInternal(date, dailyInterval.get().getIntervalId(), null, null);
        }
        
        System.out.println("=== HISTORICAL RECOMPUTATION COMPLETE ===");
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private LocalDate getIntervalStartDate(UUID intervalId, LocalDate cronDate) {
        VotingInterval interval = votingIntervalRepository.findById(intervalId).orElseThrow();
        
        switch (interval.getName()) {
            case "Daily":
                return cronDate;
            case "Weekly":
                return cronDate.with(DayOfWeek.MONDAY);
            case "Monthly":
                return cronDate.withDayOfMonth(1);
            case "Quarterly":
                int currentQuarter = (cronDate.getMonthValue() - 1) / 3;
                return cronDate.withMonth(currentQuarter * 3 + 1).withDayOfMonth(1);
            case "Midterm":
                int month = cronDate.getMonthValue();
                if (month >= 7) {
                    return cronDate.withMonth(7).withDayOfMonth(1);
                } else {
                    return cronDate.withMonth(1).withDayOfMonth(1);
                }
            case "Annual":
                return cronDate.withDayOfYear(1);
            default:
                return cronDate;
        }
    }

        /**
     * An award period is only meaningful once it has fully elapsed.
     *
     * This matters more than it looks. getPeriodLeaderboard and getPastAwards both
     * auto-populate a missing Award on read. If a client asks for a period that is
     * still running, that read PERSISTS an Award row stamped with a future
     * awardDate, computed from partial data — and existsAwardForCategory then
     * blocks the nightly cron from ever recomputing it. A single request for
     * "2026 Artist of the Year" in July permanently freezes the wrong winner.
     *
     * A period ending today is NOT closed: plays, votes and likes are still landing
     * until midnight.
     */
    /**
     * Currently unused. Both read paths that called this have been reopened:
     * getPastAwards is @Transactional(readOnly = true) and getPeriodLeaderboard
     * only calls awardRepository.findByFilters, so neither can persist an Award
     * for an open period — the fabricate-on-read behaviour this guarded was
     * already removed when those methods were made pure reads.
     *
     * Kept deliberately. If a write path is ever reintroduced on a read
     * endpoint, this is the guard it needs.
     */
    @SuppressWarnings("unused")
    private boolean isPeriodClosed(LocalDate endDate) {
        return endDate != null && endDate.isBefore(LocalDate.now());
    }

    private Optional<VotingInterval> determineIntervalFromDateRange(LocalDate startDate, LocalDate endDate) {
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate);
        
        if (daysBetween == 0) {
            return votingIntervalRepository.findByName("Daily");
        } else if (daysBetween <= 7) {
            return votingIntervalRepository.findByName("Weekly");
        } else if (daysBetween <= 31) {
            return votingIntervalRepository.findByName("Monthly");
        } else if (daysBetween <= 92) {
            return votingIntervalRepository.findByName("Quarterly");
        } else if (daysBetween <= 183) {
            return votingIntervalRepository.findByName("Midterm");
        } else {
            return votingIntervalRepository.findByName("Annual");
        }
    }

    private List<Award> createFallbackAwards(String type, UUID jurisdictionId, UUID intervalId, LocalDate awardDate) {
        List<Award> fallbackAwards = new ArrayList<>();
        
        List<UUID> jurisdictionIds = getJurisdictionAndAllChildren(jurisdictionId);
        
        if ("song".equals(type)) {
            List<Song> songs = songRepository.findAll().stream()
                .filter(s -> !s.isRemoved())
                .filter(s -> s.getArtist() == null || s.getArtist().getDeletedAt() == null)
                .filter(s -> s.getJurisdiction() != null && jurisdictionIds.contains(s.getJurisdiction().getJurisdictionId()))
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                .limit(10)
                .collect(Collectors.toList());
            
            for (Song song : songs) {
                Award award = Award.builder()
                    .targetType("song")
                    .targetId(song.getSongId())
                    .genre(song.getGenre())
                    .jurisdiction(jurisdictionRepository.findById(jurisdictionId).orElse(null))
                    .interval(votingIntervalRepository.findById(intervalId).orElse(null))
                    .awardDate(awardDate)
                    .votesCount(0)
                    .weightedPoints(0)
                    .playsCount(0)
                    .likesCount(0)
                    .engagementScore(song.getScore())
                    .weight(100)
                    .determinationMethod("FALLBACK")
                    .tiedCandidatesCount(0)
                    .caption("No votes cast - showing top by score")
                    .build();
                fallbackAwards.add(award);
            }
        } else if ("artist".equals(type)) {
            List<User> artists = userRepository.findAll().stream()
                .filter(u -> u.getRole() == User.Role.artist)
                .filter(u -> u.getDeletedAt() == null)
                .filter(u -> u.getJurisdiction() != null && jurisdictionIds.contains(u.getJurisdiction().getJurisdictionId()))
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                .limit(10)
                .collect(Collectors.toList());
            
            for (User artist : artists) {
                Award award = Award.builder()
                    .targetType("artist")
                    .targetId(artist.getUserId())
                    .genre(artist.getGenre())
                    .jurisdiction(jurisdictionRepository.findById(jurisdictionId).orElse(null))
                    .interval(votingIntervalRepository.findById(intervalId).orElse(null))
                    .awardDate(awardDate)
                    .votesCount(0)
                    .weightedPoints(0)
                    .playsCount(0)
                    .likesCount(0)
                    .engagementScore(artist.getScore())
                    .weight(100)
                    .determinationMethod("FALLBACK")
                    .tiedCandidatesCount(0)
                    .caption("No votes cast - showing top by score")
                    .build();
                fallbackAwards.add(award);
            }
        }
        
        return fallbackAwards;
    }

    /**
     * Moves the decided winner to the top of a past period's ranking. If the
     * winner isn't in the live ranking at all any more, it is added back from
     * the numbers stored on its award row.
     */
    private List<CandidateResult> pinWinnerFirst(List<CandidateResult> candidates, Award winner) {
        List<CandidateResult> ordered = new ArrayList<>(candidates);
        CandidateResult pinned = null;
        for (CandidateResult c : ordered) {
            if (c.targetId.equals(winner.getTargetId())) {
                pinned = c;
                break;
            }
        }
        if (pinned != null) {
            ordered.remove(pinned);
        } else {
            pinned = new CandidateResult();
            pinned.targetId = winner.getTargetId();
            pinned.rawVoteCount = winner.getVotesCount() != null ? winner.getVotesCount() : 0;
            pinned.weightedPoints = winner.getWeightedPoints() != null ? winner.getWeightedPoints() : 0;
            pinned.engagementPoints = winner.getEngagementPoints() != null ? winner.getEngagementPoints() : 0;
            pinned.totalPoints = winner.getTotalPoints() != null ? winner.getTotalPoints() : 0;
            pinned.playsCount = winner.getPlaysCount() != null ? winner.getPlaysCount() : 0;
            pinned.likesCount = winner.getLikesCount() != null ? winner.getLikesCount() : 0;
            pinned.score = winner.getEngagementScore() != null ? winner.getEngagementScore() : 0;
            pinned.seniority = winner.getWinnerSeniority();
        }
        ordered.add(0, pinned);
        return ordered;
    }

    private List<Award> populateAwardEntities(List<Award> awards) {
        for (Award award : awards) {
            if ("song".equals(award.getTargetType())) {
                songRepository.findById(award.getTargetId()).ifPresent(song -> {
                    award.setSong(song);
                    if (song.getArtist() != null) {
                        song.getArtist().getUsername();
                    }
                });
            } else if ("artist".equals(award.getTargetType())) {
                userRepository.findById(award.getTargetId()).ifPresent(award::setUser);
            }
        }
        return awards;
    }

    @Transactional(readOnly = true)
    public List<Award> getArtistAwards(UUID artistId, int limit, int offset) {
        List<Award> awards = awardRepository.findByTargetIdOrderByAwardDateDesc(artistId, PageRequest.of(offset / limit, limit));
        return populateAwardEntities(awards);
    }
    
    public List<Award> getArtistSongAwards(UUID artistId, int limit, int offset) {
        List<Award> awards = awardRepository.findSongAwardsByArtistId(
            artistId, PageRequest.of(offset / limit, limit)
        );
        return populateAwardEntities(awards);  // already handles 'song' type — sets award.song
    }

    // =========================================================================
    // INNER CLASSES
    // =========================================================================

    private static class CandidateResult {
        UUID targetId;
        int rawVoteCount;
        int weightedPoints;      // votes only, weighted by interval
        int engagementPoints;    // plays * PLAY_WEIGHT + likes * LIKE_WEIGHT
        int totalPoints;         // weightedPoints + engagementPoints — the ranking figure
        int playsCount;
        int likesCount;
        int score;
        LocalDateTime seniority;
    }

    private static class WinnerResult {
        UUID targetId;
        int rawVoteCount;
        int weightedPoints;
        int engagementPoints;
        int totalPoints;
        int playsCount;
        int likesCount;
        int score;
        LocalDateTime seniority;
        String determinationMethod;
        int tiedCandidatesCount;
    }
}