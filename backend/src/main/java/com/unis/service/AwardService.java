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

    private final CronMonitorService cronMonitorService;

    private static final java.time.ZoneId UNIS_ZONE = java.time.ZoneId.of("America/New_York");
    
    @Lazy
    @Autowired
    private AwardService self;

    @Value("${unis.auto-populate-awards:true}")
    private boolean autoPopulateAwards;

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
    // VOTE WEIGHTS - Used for calculating weighted vote points
    // =========================================================================
    private static final Map<String, Integer> VOTE_WEIGHTS = Map.of(
        "Annual", 250,
        "Midterm", 200,
        "Quarterly", 60,
        "Monthly", 25,
        "Weekly", 20,
        "Daily", 10
    );

    // =========================================================================
    // ENGAGEMENT WEIGHTS - Plays and likes now score directly, they are no
    // longer a zero-vote fallback. A candidate's total is:
    //
    //     total_points = weighted_vote_points
    //                  + (plays * PLAY_WEIGHT)
    //                  + (likes * LIKE_WEIGHT)
    //
    // Votes stay primary by weight (one daily vote = 10 plays) and by the
    // tiebreaker order below total_points, but enough engagement can now beat
    // a thin vote lead. Raw plays are counted deliberately, not distinct
    // listeners: a superfan on repeat is meant to be worth more than a
    // one-and-done listener. MediaService's 30-minute per-song cooldown is
    // what bounds this.
    // =========================================================================
    private static final int PLAY_WEIGHT = 1;
    private static final int LIKE_WEIGHT = 5;

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
        
        if (awards.isEmpty() && autoPopulateAwards) {
            self.computeAwardsForDate(end, intervalId, jurisdictionId, null);
            awards = awardRepository.findTopByPeriod(jurisdictionId, intervalId, start, end);
        }
        
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
 * Reuses getRankedCandidates so the ranking matches what the cron uses to
 * determine winners, including engagement points.
 */
@Transactional(readOnly = true)
public PeriodLeaderboardDto getPeriodLeaderboard(String type, LocalDate startDate, LocalDate endDate,
                                                  UUID jurisdictionId, UUID genreId, UUID intervalId,
                                                  int limit) {
    log.info("getPeriodLeaderboard: type={}, range={}..{}, jurisdiction={}, genre={}, interval={}",
                type, startDate, endDate, jurisdictionId, genreId, intervalId);

    // 1. Fetch the saved winner Award (auto-populate if missing — same pattern as getPastAwards)
    List<Award> awards = awardRepository.findByFilters(
        type, jurisdictionId, genreId, intervalId, startDate, endDate);

    if (awards.isEmpty() && autoPopulateAwards) {
        log.info("No winner award found — triggering computation");
        self.computeAndSaveAwardsInNewTransaction(endDate, intervalId, jurisdictionId, genreId);
        awards = awardRepository.findByFilters(
            type, jurisdictionId, genreId, intervalId, startDate, endDate);
    }

    Award winner = awards.isEmpty() ? null : awards.get(0);
    if (winner != null) {
        populateAwardEntities(awards);
    }

    // 2. Compute the full ranked candidate list, live, using the exact query
    //    the cron uses — total points, then the tiebreaker cascade. The
    //    standings a user reads mid-period therefore predict the winner.
    List<CandidateResult> candidates = getRankedCandidates(
        type, jurisdictionId, genreId, startDate, endDate);

    // 4. Build response
    int totalVotes = candidates.stream().mapToInt(c -> c.rawVoteCount).sum();
    UUID winnerId = winner != null
        ? winner.getTargetId()
        : (candidates.isEmpty() ? null : candidates.get(0).targetId);

    List<LeaderboardEntryDto> leaderboard = new ArrayList<>();
    int rank = 1;
    for (CandidateResult c : candidates.stream().limit(limit).collect(Collectors.toList())) {
        boolean isWinner = c.targetId.equals(winnerId);
        leaderboard.add(hydrateLeaderboardEntry(c, type, rank++, isWinner, winner));
    }

    return PeriodLeaderboardDto.builder()
        .winner(winner)
        .leaderboard(leaderboard)
        .totalVotes(totalVotes)
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
    // TRANSACTION BOUNDARY FIX
    // =========================================================================
    
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    @CacheEvict(value = {"awards", "leaderboards"}, allEntries = true)
    public void computeAndSaveAwardsInNewTransaction(LocalDate awardDate, UUID intervalId, 
                                                       UUID jurisdictionId, UUID genreId) {
        System.out.println(">>> NEW TRANSACTION STARTED - readOnly=false <<<");
        System.out.println("Computing awards for date: " + awardDate);
        
        computeAwardsInternal(awardDate, intervalId, jurisdictionId, genreId);
        
        System.out.println(">>> TRANSACTION WILL COMMIT NOW <<<");
    }

    // =========================================================================
    // CORE AWARD COMPUTATION
    // =========================================================================

    @Transactional(readOnly = false)
    @CacheEvict(value = {"awards", "leaderboards"}, allEntries = true)
    public void computeAwardsForDate(LocalDate awardDate, UUID intervalId, UUID jurisdictionId, UUID genreId) {
        System.out.println(">>> computeAwardsForDate called with @Transactional(readOnly=false)");
        computeAwardsInternal(awardDate, intervalId, jurisdictionId, genreId);
    }

    private void computeAwardsInternal(LocalDate awardDate, UUID intervalId, UUID jurisdictionId, UUID genreId) {
        List<UUID> jurisdictions;
        if (jurisdictionId != null) {
            Jurisdiction jur = jurisdictionRepository.findById(jurisdictionId).orElse(null);
            if (jur == null || !Boolean.TRUE.equals(jur.getVotingEnabled())) {
                System.out.println("Skipping non-voting-enabled jurisdiction: " + jurisdictionId);
                return;
            }
            jurisdictions = List.of(jurisdictionId);
        } else {
            jurisdictions = jurisdictionRepository.findVotingEnabledJurisdictionIds();
        }

        List<UUID> genres = genreId != null ? List.of(genreId) : genreRepository.findAllGenreIds();

        VotingInterval interval = votingIntervalRepository.findById(intervalId).orElse(null);
        if (interval == null) {
            System.out.println("Interval not found: " + intervalId);
            return;
        }

        LocalDate startDate = getIntervalStartDate(intervalId, awardDate);

        System.out.println("Computing awards for " + interval.getName() + " from " + startDate + " to " + awardDate);
        System.out.println("Jurisdictions to process: " + jurisdictions.size());
        System.out.println("Genres to process: " + genres.size());

        int awardsCreated = 0;
        
        for (UUID jurId : jurisdictions) {
            for (UUID genId : genres) {
                if (computeSingleWinnerAward("song", jurId, genId, intervalId, startDate, awardDate, interval)) {
                    awardsCreated++;
                }
                
                if (computeSingleWinnerAward("artist", jurId, genId, intervalId, startDate, awardDate, interval)) {
                    awardsCreated++;
                }
            }
        }
        
        System.out.println(">>> Total awards created in this transaction: " + awardsCreated);
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
    // RANKED CANDIDATE AGGREGATION (VOTES + ENGAGEMENT, ONE PASS)
    // =========================================================================

    /**
     * Rank every eligible candidate for one category on total points.
     *
     * The candidate set is the POPULATION, not the vote list. The old query
     * selected FROM votes, so an artist with 200 plays and no votes was never a
     * candidate at all — they could only surface through a separate zero-vote
     * fallback query that ran when nobody had voted. That is why one vote beat
     * two hundred plays. Here the population is the driving table and votes,
     * plays and likes are LEFT JOINed onto it.
     *
     *   total_points = weighted_vote_points
     *                + plays * PLAY_WEIGHT
     *                + likes * LIKE_WEIGHT
     *
     * Vote weights are unchanged: Annual 250, Midterm 200, Quarterly 60,
     * Monthly 25, Weekly 20, Daily 10.
     *
     * Ordering is total → vote points → plays → likes → score → seniority.
     * Vote points sitting directly under total is what keeps votes primary:
     * when two candidates reach the same total, the one who got there on votes
     * takes it.
     *
     * Eligibility is unchanged from the old pair of queries:
     *  - votes are aggregated bidirectionally (this jurisdiction + all children
     *    + all ancestors), so a vote cast at Harlem counts for a Downtown
     *    Harlem artist and vice versa;
     *  - the artist must live in this jurisdiction or below;
     *  - a candidate qualifies for the genre either by their own genre_id or by
     *    having received a vote in that genre — the same union the two old
     *    queries produced between them, kept so nothing that used to be
     *    eligible silently stops being eligible.
     *
     * Date bucketing deliberately still uses DATE(col) BETWEEN, matching the
     * old queries exactly. Switching to half-open timestamp ranges would be
     * faster and index-sargable, but it would also move which calendar day a
     * play lands in. That is a separate decision, not something to change
     * underneath a scoring rewrite.
     */
    @SuppressWarnings("unchecked")
    private List<CandidateResult> getRankedCandidates(String targetType, UUID jurisdictionId,
                                                       UUID genreId, LocalDate startDate,
                                                       LocalDate endDate) {
        // Bidirectional jurisdiction set for vote aggregation
        Set<UUID> allRelatedJurisdictions = new HashSet<>();
        allRelatedJurisdictions.add(jurisdictionId);
        allRelatedJurisdictions.addAll(getJurisdictionAndAllChildren(jurisdictionId));
        allRelatedJurisdictions.addAll(getJurisdictionAncestors(jurisdictionId));

        // Residency set for eligibility (this jurisdiction and below only)
        List<UUID> thisAndChildren = getJurisdictionAndAllChildren(jurisdictionId);

        System.out.println("Ranking " + targetType + " candidates for " + jurisdictionId +
                          ": votes from " + allRelatedJurisdictions.size() + " jurisdictions, " +
                          "residents of " + thisAndChildren.size());

        String voteWeightCase = """
                        SUM(CASE
                            WHEN vi.name = 'Annual'    THEN 250
                            WHEN vi.name = 'Midterm'   THEN 200
                            WHEN vi.name = 'Quarterly' THEN 60
                            WHEN vi.name = 'Monthly'   THEN 25
                            WHEN vi.name = 'Weekly'    THEN 20
                            WHEN vi.name = 'Daily'     THEN 10
                            ELSE 0
                        END)""";

        String sql;

        if ("song".equals(targetType)) {
            sql = """
                SELECT * FROM (
                    SELECT
                        s.song_id                          AS target_id,
                        COALESCE(v.raw_vote_count, 0)      AS raw_vote_count,
                        COALESCE(v.weighted_points, 0)     AS weighted_points,
                        COALESCE(p.plays_count, 0)         AS plays_count,
                        COALESCE(l.likes_count, 0)         AS likes_count,
                        COALESCE(s.score, 0)               AS score,
                        s.created_at                       AS seniority,
                        (COALESCE(p.plays_count, 0) * :playWeight
                         + COALESCE(l.likes_count, 0) * :likeWeight)        AS engagement_points,
                        (COALESCE(v.weighted_points, 0)
                         + COALESCE(p.plays_count, 0) * :playWeight
                         + COALESCE(l.likes_count, 0) * :likeWeight)        AS total_points
                    FROM songs s
                    JOIN users artist ON s.artist_id = artist.user_id
                    LEFT JOIN (
                        SELECT
                            v.target_id,
                            COUNT(v.vote_id) AS raw_vote_count,
                            __VOTE_WEIGHT_CASE__ AS weighted_points
                        FROM votes v
                        JOIN voting_intervals vi ON v.interval_id = vi.interval_id
                        WHERE v.target_type = 'song'
                          AND v.genre_id = :genreId
                          AND DATE(v.vote_date) BETWEEN :startDate AND :endDate
                          AND v.jurisdiction_id IN (:allRelatedJurisdictions)
                        GROUP BY v.target_id
                    ) v ON v.target_id = s.song_id
                    LEFT JOIN (
                        SELECT sp.song_id, COUNT(*) AS plays_count
                        FROM song_plays sp
                        WHERE sp.played_at IS NOT NULL
                          AND DATE(sp.played_at) BETWEEN :startDate AND :endDate
                        GROUP BY sp.song_id
                    ) p ON p.song_id = s.song_id
                    LEFT JOIN (
                        SELECT l.media_id, COUNT(*) AS likes_count
                        FROM likes l
                        WHERE l.media_type = 'song'
                          AND DATE(l.created_at) BETWEEN :startDate AND :endDate
                        GROUP BY l.media_id
                    ) l ON l.media_id = s.song_id
                    WHERE s.deleted_at IS NULL
                      AND artist.deleted_at IS NULL
                      AND artist.jurisdiction_id IN (:thisAndChildren)
                      AND (s.genre_id = :genreId OR v.raw_vote_count IS NOT NULL)
                ) c
                ORDER BY total_points DESC, weighted_points DESC, plays_count DESC,
                         likes_count DESC, score DESC, seniority ASC
                LIMIT 50
            """.replace("__VOTE_WEIGHT_CASE__", voteWeightCase);
        } else {
            sql = """
                SELECT * FROM (
                    SELECT
                        u.user_id                          AS target_id,
                        COALESCE(v.raw_vote_count, 0)      AS raw_vote_count,
                        COALESCE(v.weighted_points, 0)     AS weighted_points,
                        COALESCE(p.plays_count, 0)         AS plays_count,
                        COALESCE(l.likes_count, 0)         AS likes_count,
                        COALESCE(u.score, 0)               AS score,
                        u.created_at                       AS seniority,
                        (COALESCE(p.plays_count, 0) * :playWeight
                         + COALESCE(l.likes_count, 0) * :likeWeight)        AS engagement_points,
                        (COALESCE(v.weighted_points, 0)
                         + COALESCE(p.plays_count, 0) * :playWeight
                         + COALESCE(l.likes_count, 0) * :likeWeight)        AS total_points
                    FROM users u
                    LEFT JOIN (
                        SELECT
                            v.target_id,
                            COUNT(v.vote_id) AS raw_vote_count,
                            __VOTE_WEIGHT_CASE__ AS weighted_points
                        FROM votes v
                        JOIN voting_intervals vi ON v.interval_id = vi.interval_id
                        WHERE v.target_type = 'artist'
                          AND v.genre_id = :genreId
                          AND DATE(v.vote_date) BETWEEN :startDate AND :endDate
                          AND v.jurisdiction_id IN (:allRelatedJurisdictions)
                        GROUP BY v.target_id
                    ) v ON v.target_id = u.user_id
                    LEFT JOIN (
                        SELECT song.artist_id, COUNT(*) AS plays_count
                        FROM song_plays sp
                        JOIN songs song ON sp.song_id = song.song_id
                        WHERE sp.played_at IS NOT NULL
                          AND song.deleted_at IS NULL
                          AND DATE(sp.played_at) BETWEEN :startDate AND :endDate
                        GROUP BY song.artist_id
                    ) p ON p.artist_id = u.user_id
                    LEFT JOIN (
                        SELECT song.artist_id, COUNT(*) AS likes_count
                        FROM likes l
                        JOIN songs song ON l.media_id = song.song_id
                        WHERE l.media_type = 'song'
                          AND song.deleted_at IS NULL
                          AND DATE(l.created_at) BETWEEN :startDate AND :endDate
                        GROUP BY song.artist_id
                    ) l ON l.artist_id = u.user_id
                    WHERE u.role = 'artist'
                      AND u.deleted_at IS NULL
                      AND u.jurisdiction_id IN (:thisAndChildren)
                      AND (u.genre_id = :genreId OR v.raw_vote_count IS NOT NULL)
                ) c
                ORDER BY total_points DESC, weighted_points DESC, plays_count DESC,
                         likes_count DESC, score DESC, seniority ASC
                LIMIT 50
            """.replace("__VOTE_WEIGHT_CASE__", voteWeightCase);
        }

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("allRelatedJurisdictions", new ArrayList<>(allRelatedJurisdictions));
        query.setParameter("thisAndChildren", thisAndChildren);
        query.setParameter("genreId", genreId);
        query.setParameter("startDate", startDate);
        query.setParameter("endDate", endDate);
        query.setParameter("playWeight", PLAY_WEIGHT);
        query.setParameter("likeWeight", LIKE_WEIGHT);

        List<Object[]> results = query.getResultList();

        List<CandidateResult> candidates = new ArrayList<>();
        for (Object[] row : results) {
            CandidateResult candidate = new CandidateResult();
            candidate.targetId         = (UUID) row[0];
            candidate.rawVoteCount     = ((Number) row[1]).intValue();
            candidate.weightedPoints   = ((Number) row[2]).intValue();
            candidate.playsCount       = ((Number) row[3]).intValue();
            candidate.likesCount       = ((Number) row[4]).intValue();
            candidate.score            = ((Number) row[5]).intValue();
            candidate.seniority        = row[6] != null
                    ? ((java.sql.Timestamp) row[6]).toLocalDateTime()
                    : LocalDateTime.now();
            candidate.engagementPoints = ((Number) row[7]).intValue();
            candidate.totalPoints      = ((Number) row[8]).intValue();
            candidates.add(candidate);
        }

        System.out.println("Ranked " + candidates.size() + " candidate(s) for " + targetType);
        if (!candidates.isEmpty()) {
            CandidateResult top = candidates.get(0);
            System.out.println("Leader: " + top.targetId + " — total " + top.totalPoints +
                              " (" + top.weightedPoints + " vote pts from " + top.rawVoteCount +
                              " votes, " + top.engagementPoints + " engagement pts from " +
                              top.playsCount + " plays / " + top.likesCount + " likes)");
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
    // SCHEDULED CRON JOBS (with CronMonitorService tracking)
    // =========================================================================

    @Scheduled(cron = "0 1 0 * * ?", zone = "America/New_York")
    @Transactional(readOnly = false)
    public void computeDailyAwards() {
        CronExecution exec = cronMonitorService.startExecution("DAILY_AWARDS");
        try {
            LocalDate yesterday = LocalDate.now(UNIS_ZONE).minusDays(1);
            System.out.println("=== DAILY AWARD CRON: Computing for " + yesterday + " ===");

            Optional<VotingInterval> dailyInterval = votingIntervalRepository.findByName("Daily");
            if (dailyInterval.isEmpty()) {
                System.out.println("ERROR: Daily interval not found!");
                cronMonitorService.markFailed(exec, "Daily interval not found");
                return;
            }

            long countBefore = awardRepository.count();
            computeAwardsInternal(yesterday, dailyInterval.get().getIntervalId(), null, null);
            songRepository.resetPlaysToday(LocalDate.now(UNIS_ZONE));
            long countAfter = awardRepository.count();

            int created = (int) (countAfter - countBefore);
            cronMonitorService.markSuccess(exec, created);
            System.out.println("=== DAILY AWARD CRON COMPLETE: " + created + " awards created ===");
        } catch (Exception e) {
            cronMonitorService.markFailed(exec, e.getMessage());
            System.out.println("=== DAILY AWARD CRON FAILED: " + e.getMessage() + " ===");
            throw e;
        }
    }

    @Scheduled(cron = "0 1 0 * * MON", zone = "America/New_York")
    @Transactional(readOnly = false)
    public void computeWeeklyAwards() {
        CronExecution exec = cronMonitorService.startExecution("WEEKLY_AWARDS");
        try {
            System.out.println("=== WEEKLY AWARD CRON ===");
            Optional<VotingInterval> weekly = votingIntervalRepository.findByName("Weekly");
            if (weekly.isEmpty()) {
                cronMonitorService.markFailed(exec, "Weekly interval not found");
                return;
            }

            long countBefore = awardRepository.count();
            computeAwardsInternal(LocalDate.now(UNIS_ZONE).minusDays(1), weekly.get().getIntervalId(), null, null);
            long countAfter = awardRepository.count();

            cronMonitorService.markSuccess(exec, (int) (countAfter - countBefore));
            System.out.println("=== WEEKLY AWARD CRON COMPLETE ===");
        } catch (Exception e) {
            cronMonitorService.markFailed(exec, e.getMessage());
            System.out.println("=== WEEKLY AWARD CRON FAILED: " + e.getMessage() + " ===");
            throw e;
        }
    }

    @Scheduled(cron = "0 1 0 1 * ?", zone = "America/New_York")
    @Transactional(readOnly = false)
    public void computeMonthlyAwards() {
        CronExecution exec = cronMonitorService.startExecution("MONTHLY_AWARDS");
        try {
            System.out.println("=== MONTHLY AWARD CRON ===");
            Optional<VotingInterval> monthly = votingIntervalRepository.findByName("Monthly");
            if (monthly.isEmpty()) {
                cronMonitorService.markFailed(exec, "Monthly interval not found");
                return;
            }

            long countBefore = awardRepository.count();
            computeAwardsInternal(LocalDate.now(UNIS_ZONE).minusDays(1), monthly.get().getIntervalId(), null, null);
            long countAfter = awardRepository.count();

            cronMonitorService.markSuccess(exec, (int) (countAfter - countBefore));
            System.out.println("=== MONTHLY AWARD CRON COMPLETE ===");
        } catch (Exception e) {
            cronMonitorService.markFailed(exec, e.getMessage());
            System.out.println("=== MONTHLY AWARD CRON FAILED: " + e.getMessage() + " ===");
            throw e;
        }
    }

    @Scheduled(cron = "0 1 0 1 * ?", zone = "America/New_York")
    @Transactional(readOnly = false)
    public void computeQuarterlyAwards() {
        LocalDate now = LocalDate.now(UNIS_ZONE);
        int month = now.getMonthValue();
        if (month == 1 || month == 4 || month == 7 || month == 10) {
            CronExecution exec = cronMonitorService.startExecution("QUARTERLY_AWARDS");
            try {
                System.out.println("=== QUARTERLY AWARD CRON ===");
                Optional<VotingInterval> quarterly = votingIntervalRepository.findByName("Quarterly");
                if (quarterly.isEmpty()) {
                    cronMonitorService.markFailed(exec, "Quarterly interval not found");
                    return;
                }

                long countBefore = awardRepository.count();
                computeAwardsInternal(now.minusDays(1), quarterly.get().getIntervalId(), null, null);
                long countAfter = awardRepository.count();

                cronMonitorService.markSuccess(exec, (int) (countAfter - countBefore));
                System.out.println("=== QUARTERLY AWARD CRON COMPLETE ===");
            } catch (Exception e) {
                cronMonitorService.markFailed(exec, e.getMessage());
                System.out.println("=== QUARTERLY AWARD CRON FAILED: " + e.getMessage() + " ===");
                throw e;
            }
        }
    }

    @Scheduled(cron = "0 1 0 1 * ?", zone = "America/New_York")
    @Transactional(readOnly = false)
    public void computeMidtermAwards() {
        LocalDate now = LocalDate.now(UNIS_ZONE);
        int month = now.getMonthValue();
        if (month == 1 || month == 7) {
            CronExecution exec = cronMonitorService.startExecution("MIDTERM_AWARDS");
            try {
                System.out.println("=== MIDTERM AWARD CRON ===");
                Optional<VotingInterval> midterm = votingIntervalRepository.findByName("Midterm");
                if (midterm.isEmpty()) {
                    cronMonitorService.markFailed(exec, "Midterm interval not found");
                    return;
                }

                long countBefore = awardRepository.count();
                computeAwardsInternal(now.minusDays(1), midterm.get().getIntervalId(), null, null);
                long countAfter = awardRepository.count();

                cronMonitorService.markSuccess(exec, (int) (countAfter - countBefore));
                System.out.println("=== MIDTERM AWARD CRON COMPLETE ===");
            } catch (Exception e) {
                cronMonitorService.markFailed(exec, e.getMessage());
                System.out.println("=== MIDTERM AWARD CRON FAILED: " + e.getMessage() + " ===");
                throw e;
            }
        }
    }

    @Scheduled(cron = "0 1 0 1 1 ?", zone = "America/New_York")
    @Transactional(readOnly = false)
    public void computeAnnualAwards() {
        CronExecution exec = cronMonitorService.startExecution("ANNUAL_AWARDS");
        try {
            System.out.println("=== ANNUAL AWARD CRON ===");
            Optional<VotingInterval> annual = votingIntervalRepository.findByName("Annual");
            if (annual.isEmpty()) {
                cronMonitorService.markFailed(exec, "Annual interval not found");
                return;
            }

            long countBefore = awardRepository.count();
            computeAwardsInternal(LocalDate.now(UNIS_ZONE).minusDays(1), annual.get().getIntervalId(), null, null);
            long countAfter = awardRepository.count();

            cronMonitorService.markSuccess(exec, (int) (countAfter - countBefore));
            System.out.println("=== ANNUAL AWARD CRON COMPLETE ===");
        } catch (Exception e) {
            cronMonitorService.markFailed(exec, e.getMessage());
            System.out.println("=== ANNUAL AWARD CRON FAILED: " + e.getMessage() + " ===");
            throw e;
        }
    }

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