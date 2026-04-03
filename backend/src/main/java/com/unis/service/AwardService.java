package com.unis.service;

import com.unis.entity.Award;
import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.entity.Genre;
import com.unis.entity.CronExecution;
import com.unis.service.CronMonitorService;
import com.unis.entity.Jurisdiction;
import com.unis.entity.VotingInterval;
import com.unis.repository.AwardRepository;
import com.unis.repository.VoteRepository;
import com.unis.repository.VotingIntervalRepository;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.GenreRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.UserRepository;
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
        
        LocalDate start = LocalDate.now().minusDays(30);
        LocalDate end = LocalDate.now();
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
        System.out.println("=== getPastAwards CALLED ===");
        System.out.println("Type: " + type + ", Start: " + startDate + ", End: " + endDate);
        System.out.println("Jurisdiction: " + jurisdictionId + ", Genre: " + genreId + ", Interval: " + intervalId);
        
        if (intervalId == null) {
            Optional<VotingInterval> intervalOpt = determineIntervalFromDateRange(startDate, endDate);
            intervalId = intervalOpt.map(VotingInterval::getIntervalId).orElse(null);
        }
        
        if (intervalId == null) {
            System.out.println("Could not determine interval for date range");
            return new ArrayList<>();
        }

        List<Award> awards = awardRepository.findByFilters(type, jurisdictionId, genreId, intervalId, startDate, endDate);
        
        System.out.println("Initial query found " + awards.size() + " awards");
        
        if (awards.isEmpty() && autoPopulateAwards) {
            System.out.println("=== NO AWARDS FOUND - TRIGGERING COMPUTATION ===");
            
            UUID finalIntervalId = intervalId;
            self.computeAndSaveAwardsInNewTransaction(endDate, finalIntervalId, jurisdictionId, genreId);
            
            awards = awardRepository.findByFilters(type, jurisdictionId, genreId, finalIntervalId, startDate, endDate);
            
            System.out.println("=== AFTER COMPUTATION: Found " + awards.size() + " awards ===");
        }
        
        if (awards.isEmpty()) {
            System.out.println("Still no awards after computation - creating fallback display (NOT saved)");
            awards = createFallbackAwards(type, jurisdictionId, intervalId, endDate);
            if (genreId != null) {
                UUID finalGenreId = genreId;
                awards = awards.stream()
                    .filter(a -> a.getGenre() != null && a.getGenre().getGenreId().equals(finalGenreId))
                    .collect(Collectors.toList());
            }
        }
        
        return populateAwardEntities(awards);
    }

    public List<Award> getPastAwards(String type, LocalDate startDate, LocalDate endDate, 
                                      UUID jurisdictionId, UUID genreId) {
        return getPastAwards(type, startDate, endDate, jurisdictionId, genreId, null);
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

        // Get candidates with full weighted scoring
        List<CandidateResult> candidates = getCandidatesWithWeightedVotes(
            targetType, jurisdictionId, genreId, startDate, awardDate
        );

        // ZERO-VOTE FALLBACK: If no votes, get candidates by plays/likes/score/seniority
        if (candidates.isEmpty()) {
            System.out.println("No votes found for " + targetType + " in jurisdiction " + jurisdictionId + " on " + awardDate);
            System.out.println("FALLBACK: Querying candidates by plays/likes/score/seniority");
            
            candidates = getCandidatesByEngagement(targetType, jurisdictionId, genreId, startDate, awardDate);
            
            if (candidates.isEmpty()) {
                System.out.println("No eligible " + targetType + "s found in jurisdiction hierarchy for fallback award");
                return false;
            }
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
                          " with " + winner.weightedPoints + " weighted points" +
                          " (" + winner.rawVoteCount + " votes, " + winner.playsCount + " plays, " + winner.likesCount + " likes)" +
                          " (determined by " + winner.determinationMethod + ")");
        
        scoreUpdateService.onAward(winner.targetId, awardPoints);
        
        return true;
    }

    // =========================================================================
    // WEIGHTED VOTE AGGREGATION WITH FULL TIEBREAKER DATA
    // =========================================================================

    /**
     * Get candidates with weighted vote points and all tiebreaker metrics.
     * 
     * Vote weights:
     * - Annual = 250 points
     * - Midterm = 200 points
     * - Quarterly = 60 points
     * - Monthly = 25 points
     * - Weekly = 20 points
     * - Daily = 10 points
     * 
     * Also fetches: plays, likes, score, seniority for tiebreaking
     */
    @SuppressWarnings("unchecked")
    private List<CandidateResult> getCandidatesWithWeightedVotes(String targetType, UUID jurisdictionId, 
                                                                  UUID genreId, LocalDate startDate, 
                                                                  LocalDate endDate) {
        // Build jurisdiction sets for bidirectional aggregation
        Set<UUID> allRelatedJurisdictions = new HashSet<>();
        allRelatedJurisdictions.add(jurisdictionId);
        
        List<UUID> children = getJurisdictionAndAllChildren(jurisdictionId);
        allRelatedJurisdictions.addAll(children);
        
        List<UUID> ancestors = getJurisdictionAncestors(jurisdictionId);
        allRelatedJurisdictions.addAll(ancestors);
        
        List<UUID> thisAndChildren = getJurisdictionAndAllChildren(jurisdictionId);

        System.out.println("Weighted vote aggregation for " + jurisdictionId + 
                          ": checking " + allRelatedJurisdictions.size() + " jurisdictions");

        String sql;
        
        if ("song".equals(targetType)) {
            sql = """
                SELECT 
                    v.target_id,
                    COUNT(v.vote_id) as raw_vote_count,
                    SUM(CASE 
                        WHEN vi.name = 'Annual' THEN 250
                        WHEN vi.name = 'Midterm' THEN 200
                        WHEN vi.name = 'Quarterly' THEN 60
                        WHEN vi.name = 'Monthly' THEN 25
                        WHEN vi.name = 'Weekly' THEN 20
                        WHEN vi.name = 'Daily' THEN 10
                        ELSE 0
                    END) as weighted_points,
                    COALESCE((
                        SELECT COUNT(*) FROM song_plays sp 
                        WHERE sp.song_id = v.target_id 
                        AND sp.played_at IS NOT NULL
                        AND DATE(sp.played_at) BETWEEN :startDate AND :endDate
                    ), 0) as plays_count,
                    COALESCE((
                        SELECT COUNT(*) FROM likes l 
                        WHERE l.media_id = v.target_id 
                        AND l.media_type = 'song'
                        AND DATE(l.created_at) BETWEEN :startDate AND :endDate
                    ), 0) as likes_count,
                    COALESCE(s.score, 0) as score,
                    s.created_at as seniority
                FROM votes v
                JOIN voting_intervals vi ON v.interval_id = vi.interval_id
                JOIN songs s ON v.target_id = s.song_id
                JOIN users artist ON s.artist_id = artist.user_id
                WHERE v.target_type = 'song'
                  AND v.genre_id = :genreId
                  AND DATE(v.vote_date) BETWEEN :startDate AND :endDate
                  AND v.jurisdiction_id IN (:allRelatedJurisdictions)
                  AND artist.jurisdiction_id IN (:thisAndChildren)
                GROUP BY v.target_id, s.score, s.created_at
                ORDER BY weighted_points DESC, plays_count DESC, likes_count DESC, score DESC, seniority ASC
            """;
        } else {
            // Artist query - plays are sum of all their songs' plays
            sql = """
                SELECT 
                    v.target_id,
                    COUNT(v.vote_id) as raw_vote_count,
                    SUM(CASE 
                        WHEN vi.name = 'Annual' THEN 250
                        WHEN vi.name = 'Midterm' THEN 200
                        WHEN vi.name = 'Quarterly' THEN 60
                        WHEN vi.name = 'Monthly' THEN 25
                        WHEN vi.name = 'Weekly' THEN 20
                        WHEN vi.name = 'Daily' THEN 10
                        ELSE 0
                    END) as weighted_points,
                    COALESCE((
                        SELECT COUNT(*) FROM song_plays sp 
                        JOIN songs song ON sp.song_id = song.song_id
                        WHERE song.artist_id = v.target_id 
                        AND sp.played_at IS NOT NULL
                        AND DATE(sp.played_at) BETWEEN :startDate AND :endDate
                    ), 0) as plays_count,
                    COALESCE((
                        SELECT COUNT(*) FROM likes l 
                        JOIN songs song ON l.media_id = song.song_id
                        WHERE song.artist_id = v.target_id 
                        AND l.media_type = 'song'
                        AND DATE(l.created_at) BETWEEN :startDate AND :endDate
                    ), 0) as likes_count,
                    COALESCE(u.score, 0) as score,
                    u.created_at as seniority
                FROM votes v
                JOIN voting_intervals vi ON v.interval_id = vi.interval_id
                JOIN users u ON v.target_id = u.user_id
                WHERE v.target_type = 'artist'
                  AND v.genre_id = :genreId
                  AND Date(v.vote_date) BETWEEN :startDate AND :endDate
                  AND (u.deleted_at IS NULL)
                  AND v.jurisdiction_id IN (:allRelatedJurisdictions)
                  AND u.jurisdiction_id IN (:thisAndChildren)
                GROUP BY v.target_id, u.score, u.created_at
                ORDER BY weighted_points DESC, plays_count DESC, likes_count DESC, score DESC, seniority ASC
            """;
        }

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("allRelatedJurisdictions", new ArrayList<>(allRelatedJurisdictions));
        query.setParameter("thisAndChildren", thisAndChildren);
        query.setParameter("genreId", genreId);
        query.setParameter("startDate", startDate);
        query.setParameter("endDate", endDate);

        List<Object[]> results = query.getResultList();
        
        List<CandidateResult> candidates = new ArrayList<>();
        for (Object[] row : results) {
            CandidateResult candidate = new CandidateResult();
            candidate.targetId = (UUID) row[0];
            candidate.rawVoteCount = ((Number) row[1]).intValue();
            candidate.weightedPoints = ((Number) row[2]).intValue();
            candidate.playsCount = ((Number) row[3]).intValue();
            candidate.likesCount = ((Number) row[4]).intValue();
            candidate.score = ((Number) row[5]).intValue();
            candidate.seniority = row[6] != null ? ((java.sql.Timestamp) row[6]).toLocalDateTime() : LocalDateTime.now();
            candidates.add(candidate);
        }

        System.out.println("Found " + candidates.size() + " candidates with weighted votes for " + targetType);
        if (!candidates.isEmpty()) {
            CandidateResult top = candidates.get(0);
            System.out.println("Top candidate: " + top.targetId + " with " + top.weightedPoints + " weighted points, " +
                              top.playsCount + " plays, " + top.likesCount + " likes, score=" + top.score);
        }
        
        return candidates;
    }

    /**
     * FALLBACK: Get candidates by engagement metrics when no votes exist.
     * Uses same tiebreaker cascade: plays → likes → score → seniority
     */
    @SuppressWarnings("unchecked")
    private List<CandidateResult> getCandidatesByEngagement(String targetType, UUID jurisdictionId, 
                                                             UUID genreId, LocalDate startDate, 
                                                             LocalDate endDate) {
        List<UUID> thisAndChildren = getJurisdictionAndAllChildren(jurisdictionId);

        System.out.println("FALLBACK: Querying " + targetType + "s by engagement from " + 
                          thisAndChildren.size() + " jurisdictions");

        String sql;
        
        if ("song".equals(targetType)) {
            sql = """
                SELECT 
                    s.song_id as target_id,
                    0 as raw_vote_count,
                    0 as weighted_points,
                    COALESCE((
                        SELECT COUNT(*) FROM song_plays sp 
                        WHERE sp.song_id = s.song_id 
                        AND sp.played_at IS NOT NULL
                        AND DATE(sp.played_at) BETWEEN :startDate AND :endDate
                    ), 0) as plays_count,
                    COALESCE((
                        SELECT COUNT(*) FROM likes l 
                        WHERE l.media_id = s.song_id 
                        AND l.media_type = 'song'
                        AND DATE(l.created_at) BETWEEN :startDate AND :endDate
                    ), 0) as likes_count,
                    COALESCE(s.score, 0) as score,
                    s.created_at as seniority
                FROM songs s
                JOIN users artist ON s.artist_id = artist.user_id
                WHERE s.genre_id = :genreId
                  AND artist.jurisdiction_id IN (:thisAndChildren)
                ORDER BY plays_count DESC, likes_count DESC, score DESC, seniority ASC
                LIMIT 10
            """;
        } else {
            sql = """
                SELECT 
                    u.user_id as target_id,
                    0 as raw_vote_count,
                    0 as weighted_points,
                    COALESCE((
                        SELECT COUNT(*) FROM song_plays sp 
                        JOIN songs song ON sp.song_id = song.song_id
                        WHERE song.artist_id = u.user_id 
                        AND sp.played_at IS NOT NULL
                        AND DATE(sp.played_at) BETWEEN :startDate AND :endDate
                    ), 0) as plays_count,
                    COALESCE((
                        SELECT COUNT(*) FROM likes l 
                        JOIN songs song ON l.media_id = song.song_id
                        WHERE song.artist_id = u.user_id 
                        AND l.media_type = 'song'
                        AND DATE(l.created_at) BETWEEN :startDate AND :endDate
                    ), 0) as likes_count,
                    COALESCE(u.score, 0) as score,
                    u.created_at as seniority
                FROM users u
                WHERE u.role = 'artist'
                  AND u.genre_id = :genreId
                  AND u.deleted_at IS NULL
                  AND u.jurisdiction_id IN (:thisAndChildren)
                ORDER BY plays_count DESC, likes_count DESC, score DESC, seniority ASC
                LIMIT 10
            """;
        }

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("thisAndChildren", thisAndChildren);
        query.setParameter("genreId", genreId);
        query.setParameter("startDate", startDate);
        query.setParameter("endDate", endDate);

        List<Object[]> results = query.getResultList();
        
        List<CandidateResult> candidates = new ArrayList<>();
        for (Object[] row : results) {
            CandidateResult candidate = new CandidateResult();
            candidate.targetId = (UUID) row[0];
            candidate.rawVoteCount = ((Number) row[1]).intValue();
            candidate.weightedPoints = ((Number) row[2]).intValue();
            candidate.playsCount = ((Number) row[3]).intValue();
            candidate.likesCount = ((Number) row[4]).intValue();
            candidate.score = ((Number) row[5]).intValue();
            candidate.seniority = row[6] != null ? ((java.sql.Timestamp) row[6]).toLocalDateTime() : LocalDateTime.now();
            candidates.add(candidate);
        }

        System.out.println("FALLBACK: Found " + candidates.size() + " candidate(s) by engagement for " + targetType);
        return candidates;
    }

    // =========================================================================
    // WINNER DETERMINATION WITH FULL TIEBREAKER CASCADE
    // =========================================================================

    /**
     * Determine winner using the full tiebreaker cascade:
     * 1. Weighted vote points (primary)
     * 2. Song plays during interval
     * 3. Likes during interval
     * 4. Platform score
     * 5. Seniority (oldest wins)
     */
    private WinnerResult determineWinner(List<CandidateResult> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }

        CandidateResult topCandidate = candidates.get(0);
        
        WinnerResult winner = new WinnerResult();
        winner.targetId = topCandidate.targetId;
        winner.rawVoteCount = topCandidate.rawVoteCount;
        winner.weightedPoints = topCandidate.weightedPoints;
        winner.playsCount = topCandidate.playsCount;
        winner.likesCount = topCandidate.likesCount;
        winner.score = topCandidate.score;
        winner.seniority = topCandidate.seniority;

        // Check if this is a zero-vote scenario (fallback)
        if (topCandidate.weightedPoints == 0) {
            winner.determinationMethod = "FALLBACK";
            winner.tiedCandidatesCount = 0;
            return winner;
        }

        // Count ties at each level
        int tiedOnWeightedPoints = 0;
        int tiedOnPlays = 0;
        int tiedOnLikes = 0;
        int tiedOnScore = 0;

        for (CandidateResult c : candidates) {
            if (c.weightedPoints == topCandidate.weightedPoints) {
                tiedOnWeightedPoints++;
                if (c.playsCount == topCandidate.playsCount) {
                    tiedOnPlays++;
                    if (c.likesCount == topCandidate.likesCount) {
                        tiedOnLikes++;
                        if (c.score == topCandidate.score) {
                            tiedOnScore++;
                        }
                    }
                }
            }
        }

        // Determine which level broke the tie
        if (tiedOnWeightedPoints == 1) {
            winner.determinationMethod = "WEIGHTED_VOTES";
            winner.tiedCandidatesCount = 0;
        } else if (tiedOnPlays == 1) {
            winner.determinationMethod = "PLAYS";
            winner.tiedCandidatesCount = tiedOnWeightedPoints;
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
                          " (tied candidates: " + winner.tiedCandidatesCount + ")");

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

    @Scheduled(cron = "0 1 0 * * ?")
    @Transactional(readOnly = false)
    public void computeDailyAwards() {
        CronExecution exec = cronMonitorService.startExecution("DAILY_AWARDS");
        try {
            LocalDate yesterday = LocalDate.now().minusDays(1);
            System.out.println("=== DAILY AWARD CRON: Computing for " + yesterday + " ===");

            Optional<VotingInterval> dailyInterval = votingIntervalRepository.findByName("Daily");
            if (dailyInterval.isEmpty()) {
                System.out.println("ERROR: Daily interval not found!");
                cronMonitorService.markFailed(exec, "Daily interval not found");
                return;
            }

            long countBefore = awardRepository.count();
            computeAwardsInternal(yesterday, dailyInterval.get().getIntervalId(), null, null);
            songRepository.resetPlaysToday(LocalDate.now());
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

    @Scheduled(cron = "0 1 0 * * MON")
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
            computeAwardsInternal(LocalDate.now().minusDays(1), weekly.get().getIntervalId(), null, null);
            long countAfter = awardRepository.count();

            cronMonitorService.markSuccess(exec, (int) (countAfter - countBefore));
            System.out.println("=== WEEKLY AWARD CRON COMPLETE ===");
        } catch (Exception e) {
            cronMonitorService.markFailed(exec, e.getMessage());
            System.out.println("=== WEEKLY AWARD CRON FAILED: " + e.getMessage() + " ===");
            throw e;
        }
    }

    @Scheduled(cron = "0 1 0 1 * ?")
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
            computeAwardsInternal(LocalDate.now().minusDays(1), monthly.get().getIntervalId(), null, null);
            long countAfter = awardRepository.count();

            cronMonitorService.markSuccess(exec, (int) (countAfter - countBefore));
            System.out.println("=== MONTHLY AWARD CRON COMPLETE ===");
        } catch (Exception e) {
            cronMonitorService.markFailed(exec, e.getMessage());
            System.out.println("=== MONTHLY AWARD CRON FAILED: " + e.getMessage() + " ===");
            throw e;
        }
    }

    @Scheduled(cron = "0 1 0 1 * ?")
    @Transactional(readOnly = false)
    public void computeQuarterlyAwards() {
        LocalDate now = LocalDate.now();
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

    @Scheduled(cron = "0 1 0 1 * ?")
    @Transactional(readOnly = false)
    public void computeMidtermAwards() {
        LocalDate now = LocalDate.now();
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

    @Scheduled(cron = "0 1 0 1 1 ?")
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
            computeAwardsInternal(LocalDate.now().minusDays(1), annual.get().getIntervalId(), null, null);
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

    // =========================================================================
    // INNER CLASSES
    // =========================================================================

    private static class CandidateResult {
        UUID targetId;
        int rawVoteCount;
        int weightedPoints;
        int playsCount;
        int likesCount;
        int score;
        LocalDateTime seniority;
    }

    private static class WinnerResult {
        UUID targetId;
        int rawVoteCount;
        int weightedPoints;
        int playsCount;
        int likesCount;
        int score;
        LocalDateTime seniority;
        String determinationMethod;
        int tiedCandidatesCount;
    }
}