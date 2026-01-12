package com.unis.service;

import com.unis.entity.Award;
import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.entity.Genre;
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
    
    // Self-injection to allow @Transactional to work on internal calls
    // @Lazy breaks the circular dependency cycle
    @Lazy
    @Autowired
    private AwardService self;

    @Value("${unis.auto-populate-awards:true}")
    private boolean autoPopulateAwards;

    // =========================================================================
    // AWARD POINT VALUES (from spec)
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
            // Use self-reference to ensure @Transactional works
            self.computeAwardsForDate(end, intervalId, jurisdictionId, null);
            awards = awardRepository.findTopByPeriod(jurisdictionId, intervalId, start, end);
        }
        
        if (awards.isEmpty()) {
            awards = createFallbackAwards(type, jurisdictionId, intervalId, end);
        }
        
        return populateAwardEntities(awards);
    }

    /**
     * Get past awards - AUTO-COMPUTES AND SAVES if none exist
     * 
     * MAIN VERSION - with intervalId parameter
     * 
     * This method starts as read-only for the initial query, then delegates to
     * a separate read-write transaction if computation is needed.
     */
    @Transactional(readOnly = true)
    public List<Award> getPastAwards(String type, LocalDate startDate, LocalDate endDate, 
                                      UUID jurisdictionId, UUID genreId, UUID intervalId) {
        System.out.println("=== getPastAwards CALLED ===");
        System.out.println("Type: " + type + ", Start: " + startDate + ", End: " + endDate);
        System.out.println("Jurisdiction: " + jurisdictionId + ", Genre: " + genreId + ", Interval: " + intervalId);
        
        // Use provided interval or determine from date range
        if (intervalId == null) {
            Optional<VotingInterval> intervalOpt = determineIntervalFromDateRange(startDate, endDate);
            intervalId = intervalOpt.map(VotingInterval::getIntervalId).orElse(null);
        }
        
        if (intervalId == null) {
            System.out.println("Could not determine interval for date range");
            return new ArrayList<>();
        }

        // Check if awards exist for this specific query
        List<Award> awards = awardRepository.findByFilters(type, jurisdictionId, genreId, intervalId, startDate, endDate);
        
        System.out.println("Initial query found " + awards.size() + " awards");
        
        // AUTO-COMPUTE AND SAVE if no awards exist
        if (awards.isEmpty() && autoPopulateAwards) {
            System.out.println("=== NO AWARDS FOUND - TRIGGERING COMPUTATION ===");
            
            // CRITICAL FIX: Call via self-reference to new method with REQUIRES_NEW transaction
            // This ensures a fresh read-write transaction is created
            UUID finalIntervalId = intervalId;
            self.computeAndSaveAwardsInNewTransaction(endDate, finalIntervalId, jurisdictionId, genreId);
            
            // Fetch the newly created awards
            awards = awardRepository.findByFilters(type, jurisdictionId, genreId, finalIntervalId, startDate, endDate);
            
            System.out.println("=== AFTER COMPUTATION: Found " + awards.size() + " awards ===");
        }
        
        // If still empty after computation, create fallback (but don't save fallbacks)
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

    /**
     * Overload for backward compatibility (without intervalId)
     * 
     * NOTE: This just delegates to the main version above
     */
    public List<Award> getPastAwards(String type, LocalDate startDate, LocalDate endDate, 
                                      UUID jurisdictionId, UUID genreId) {
        return getPastAwards(type, startDate, endDate, jurisdictionId, genreId, null);
    }

    // =========================================================================
    // TRANSACTION BOUNDARY FIX - NEW METHOD
    // =========================================================================
    
    /**
     * CRITICAL FIX: This method creates a NEW read-write transaction.
     * 
     * REQUIRES_NEW propagation means:
     * - Suspends the current read-only transaction (if any)
     * - Creates a brand new read-write transaction
     * - Commits independently when done
     * 
     * This ensures the connection is NOT read-only when we try to save awards.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    @CacheEvict(value = {"awards", "leaderboards"}, allEntries = true)
    public void computeAndSaveAwardsInNewTransaction(LocalDate awardDate, UUID intervalId, 
                                                       UUID jurisdictionId, UUID genreId) {
        System.out.println(">>> NEW TRANSACTION STARTED - readOnly=false <<<");
        System.out.println("Computing awards for date: " + awardDate);
        
        // Delegate to the main computation logic
        computeAwardsInternal(awardDate, intervalId, jurisdictionId, genreId);
        
        System.out.println(">>> TRANSACTION WILL COMMIT NOW <<<");
    }

    // =========================================================================
    // CORE AWARD COMPUTATION - INTERNAL METHOD
    // =========================================================================

    /**
     * Compute awards for a specific date.
     * 
     * This is the INTERNAL method that does the actual work.
     * It should be called from within a transaction (not directly from controllers).
     */
    @Transactional(readOnly = false)
    @CacheEvict(value = {"awards", "leaderboards"}, allEntries = true)
    public void computeAwardsForDate(LocalDate awardDate, UUID intervalId, UUID jurisdictionId, UUID genreId) {
        System.out.println(">>> computeAwardsForDate called with @Transactional(readOnly=false)");
        computeAwardsInternal(awardDate, intervalId, jurisdictionId, genreId);
    }

    /**
     * The actual computation logic - separated so it can be called from different transaction contexts
     */
    private void computeAwardsInternal(LocalDate awardDate, UUID intervalId, UUID jurisdictionId, UUID genreId) {
        // Get jurisdictions to process (only voting-enabled ones)
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

        // Get genres to process
        List<UUID> genres = genreId != null ? List.of(genreId) : genreRepository.findAllGenreIds();

        // Get interval
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
     * Returns true if an award was created.
     */
    private boolean computeSingleWinnerAward(String targetType, UUID jurisdictionId, UUID genreId,
                                              UUID intervalId, LocalDate startDate, LocalDate awardDate,
                                              VotingInterval interval) {
        
        if (awardRepository.existsAwardForCategory(targetType, jurisdictionId, genreId, intervalId, awardDate)) {
            System.out.println("Award already exists for " + targetType + " in jurisdiction " + jurisdictionId + " on " + awardDate);
            return false;
        }

        List<CandidateResult> candidates = getCandidatesWithBidirectionalVotes(
            targetType, jurisdictionId, genreId, intervalId, startDate, awardDate
        );

        // ZERO-VOTE FALLBACK: If no votes, get candidates by score + seniority
        if (candidates.isEmpty()) {
            System.out.println("No votes found for " + targetType + " in jurisdiction " + jurisdictionId + " on " + awardDate);
            System.out.println("FALLBACK: Querying candidates by score + seniority (no votes required)");
            
            candidates = getCandidatesByScoreAndSeniority(targetType, jurisdictionId, genreId);
            
            if (candidates.isEmpty()) {
                System.out.println("No eligible " + targetType + "s found in jurisdiction hierarchy for fallback award");
                return false;
            }
        }

        WinnerResult winner = determineWinner(candidates, targetType);

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
            .votesCount(winner.voteCount)
            .engagementScore(winner.score)
            .weight(awardPoints)
            .determinationMethod(winner.determinationMethod)
            .winnerSeniority(winner.seniority)
            .tiedCandidatesCount(winner.tiedCandidatesCount)
            .build();

        Award savedAward = awardRepository.save(award);
        awardRepository.flush();  // Force immediate write
        
        System.out.println("✓ Award SAVED with ID: " + savedAward.getAwardId() + " for " + targetType + 
                          " winner in jurisdiction " + jurisdictionId + 
                          " with " + winner.voteCount + " votes (determined by " + winner.determinationMethod + ")");
        
        scoreUpdateService.onAward(winner.targetId, awardPoints);
        
        return true;
    }

    /**
     * BIDIRECTIONAL VOTE AGGREGATION
     */
    @SuppressWarnings("unchecked")
    private List<CandidateResult> getCandidatesWithBidirectionalVotes(String targetType, UUID jurisdictionId, 
                                                                       UUID genreId, UUID intervalId, 
                                                                       LocalDate startDate, LocalDate endDate) {
        Set<UUID> allRelatedJurisdictions = new HashSet<>();
        
        allRelatedJurisdictions.add(jurisdictionId);
        
        List<UUID> children = getJurisdictionAndAllChildren(jurisdictionId);
        allRelatedJurisdictions.addAll(children);
        
        List<UUID> ancestors = getJurisdictionAncestors(jurisdictionId);
        allRelatedJurisdictions.addAll(ancestors);
        
        System.out.println("Bidirectional vote aggregation for " + jurisdictionId + 
                          ": checking " + allRelatedJurisdictions.size() + " jurisdictions");

        List<UUID> thisAndChildren = getJurisdictionAndAllChildren(jurisdictionId);

        String sql;
        
        if ("song".equals(targetType)) {
            sql = """
                SELECT 
                    v.target_id,
                    COUNT(v.vote_id) as vote_count,
                    COALESCE(s.score, 0) as score,
                    s.created_at as seniority
                FROM votes v
                JOIN songs s ON v.target_id = s.song_id
                JOIN users artist ON s.artist_id = artist.user_id
                WHERE v.target_type = 'song'
                  AND v.genre_id = :genreId
                  AND v.interval_id = :intervalId
                  AND v.vote_date BETWEEN :startDate AND :endDate
                  AND v.jurisdiction_id IN (:allRelatedJurisdictions)
                  AND artist.jurisdiction_id IN (:thisAndChildren)
                GROUP BY v.target_id, s.score, s.created_at
                ORDER BY vote_count DESC, score DESC, seniority ASC
            """;
        } else {
            sql = """
                SELECT 
                    v.target_id,
                    COUNT(v.vote_id) as vote_count,
                    COALESCE(u.score, 0) as score,
                    u.created_at as seniority
                FROM votes v
                JOIN users u ON v.target_id = u.user_id
                WHERE v.target_type = 'artist'
                  AND v.genre_id = :genreId
                  AND v.interval_id = :intervalId
                  AND v.vote_date BETWEEN :startDate AND :endDate
                  AND (u.deleted_at IS NULL)
                  AND v.jurisdiction_id IN (:allRelatedJurisdictions)
                  AND u.jurisdiction_id IN (:thisAndChildren)
                GROUP BY v.target_id, u.score, u.created_at
                ORDER BY vote_count DESC, score DESC, seniority ASC
            """;
        }

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("allRelatedJurisdictions", new ArrayList<>(allRelatedJurisdictions));
        query.setParameter("thisAndChildren", thisAndChildren);
        query.setParameter("genreId", genreId);
        query.setParameter("intervalId", intervalId);
        query.setParameter("startDate", startDate);
        query.setParameter("endDate", endDate);

        List<Object[]> results = query.getResultList();
        
        List<CandidateResult> candidates = new ArrayList<>();
        for (Object[] row : results) {
            CandidateResult candidate = new CandidateResult();
            candidate.targetId = (UUID) row[0];
            candidate.voteCount = ((Number) row[1]).intValue();
            candidate.score = ((Number) row[2]).intValue();
            candidate.seniority = row[3] != null ? ((java.sql.Timestamp) row[3]).toLocalDateTime() : LocalDateTime.now();
            candidates.add(candidate);
        }

        System.out.println("Found " + candidates.size() + " candidates with votes for " + targetType);
        return candidates;
    }

    /**
     * ZERO-VOTE FALLBACK: Get candidates by score + seniority (no votes required)
     * Uses same bidirectional jurisdiction logic as vote aggregation
     */
    @SuppressWarnings("unchecked")
    private List<CandidateResult> getCandidatesByScoreAndSeniority(String targetType, UUID jurisdictionId, UUID genreId) {
        // Use bidirectional jurisdiction logic (this + children + ancestors)
        Set<UUID> allRelatedJurisdictions = new HashSet<>();
        allRelatedJurisdictions.add(jurisdictionId);
        allRelatedJurisdictions.addAll(getJurisdictionAndAllChildren(jurisdictionId));
        allRelatedJurisdictions.addAll(getJurisdictionAncestors(jurisdictionId));
        
        List<UUID> thisAndChildren = getJurisdictionAndAllChildren(jurisdictionId);

        System.out.println("FALLBACK: Querying " + targetType + "s by score+seniority from " + 
                          thisAndChildren.size() + " jurisdictions (this + children)");

        String sql;
        
        if ("song".equals(targetType)) {
            sql = """
                SELECT 
                    s.song_id as target_id,
                    0 as vote_count,
                    COALESCE(s.score, 0) as score,
                    s.created_at as seniority
                FROM songs s
                JOIN users artist ON s.artist_id = artist.user_id
                WHERE s.genre_id = :genreId
                  AND artist.jurisdiction_id IN (:thisAndChildren)
                ORDER BY score DESC, seniority ASC
                LIMIT 1
            """;
        } else {
            sql = """
                SELECT 
                    u.user_id as target_id,
                    0 as vote_count,
                    COALESCE(u.score, 0) as score,
                    u.created_at as seniority
                FROM users u
                WHERE u.role = 'artist'
                  AND u.genre_id = :genreId
                  AND u.deleted_at IS NULL
                  AND u.jurisdiction_id IN (:thisAndChildren)
                ORDER BY score DESC, seniority ASC
                LIMIT 1
            """;
        }

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("thisAndChildren", thisAndChildren);
        query.setParameter("genreId", genreId);

        List<Object[]> results = query.getResultList();
        
        List<CandidateResult> candidates = new ArrayList<>();
        for (Object[] row : results) {
            CandidateResult candidate = new CandidateResult();
            candidate.targetId = (UUID) row[0];
            candidate.voteCount = ((Number) row[1]).intValue();  // Always 0
            candidate.score = ((Number) row[2]).intValue();
            candidate.seniority = row[3] != null ? ((java.sql.Timestamp) row[3]).toLocalDateTime() : LocalDateTime.now();
            candidates.add(candidate);
        }

        System.out.println("FALLBACK: Found " + candidates.size() + " candidate(s) by score+seniority for " + targetType);
        return candidates;
    }

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

    private WinnerResult determineWinner(List<CandidateResult> candidates, String targetType) {
        if (candidates.isEmpty()) {
            return null;
        }

        CandidateResult topCandidate = candidates.get(0);
        
        WinnerResult winner = new WinnerResult();
        winner.targetId = topCandidate.targetId;
        winner.voteCount = topCandidate.voteCount;
        winner.score = topCandidate.score;
        winner.seniority = topCandidate.seniority;

        // Check if this is a zero-vote scenario (fallback by score)
        if (topCandidate.voteCount == 0) {
            winner.determinationMethod = "FALLBACK";
            winner.tiedCandidatesCount = 0;
            return winner;
        }

        int tiedOnVotes = 0;
        int tiedOnVotesAndScore = 0;

        for (CandidateResult c : candidates) {
            if (c.voteCount == topCandidate.voteCount) {
                tiedOnVotes++;
                if (c.score == topCandidate.score) {
                    tiedOnVotesAndScore++;
                }
            }
        }

        if (tiedOnVotes == 1) {
            winner.determinationMethod = "VOTES";
            winner.tiedCandidatesCount = 0;
        } else if (tiedOnVotesAndScore == 1) {
            winner.determinationMethod = "SCORE";
            winner.tiedCandidatesCount = tiedOnVotes;
        } else {
            winner.determinationMethod = "SENIORITY";
            winner.tiedCandidatesCount = tiedOnVotesAndScore;
        }

        return winner;
    }

    // =========================================================================
    // SCHEDULED CRON JOBS
    // =========================================================================

    @Scheduled(cron = "0 1 0 * * ?")
    @Transactional(readOnly = false)
    public void computeDailyAwards() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        System.out.println("=== DAILY AWARD CRON: Computing for " + yesterday + " ===");
        
        Optional<VotingInterval> dailyInterval = votingIntervalRepository.findByName("Daily");
        if (dailyInterval.isEmpty()) {
            System.out.println("ERROR: Daily interval not found!");
            return;
        }

        // Call internal method directly since we're already in a transaction
        computeAwardsInternal(yesterday, dailyInterval.get().getIntervalId(), null, null);
        songRepository.resetPlaysToday(LocalDate.now());
        
        System.out.println("=== DAILY AWARD CRON COMPLETE ===");
    }

    @Scheduled(cron = "0 1 0 * * MON")
    @Transactional(readOnly = false)
    public void computeWeeklyAwards() {
        System.out.println("=== WEEKLY AWARD CRON ===");
        Optional<VotingInterval> weekly = votingIntervalRepository.findByName("Weekly");
        if (weekly.isEmpty()) return;
        computeAwardsInternal(LocalDate.now().minusDays(1), weekly.get().getIntervalId(), null, null);
    }

    @Scheduled(cron = "0 1 0 1 * ?")
    @Transactional(readOnly = false)
    public void computeMonthlyAwards() {
        System.out.println("=== MONTHLY AWARD CRON ===");
        Optional<VotingInterval> monthly = votingIntervalRepository.findByName("Monthly");
        if (monthly.isEmpty()) return;
        computeAwardsInternal(LocalDate.now().minusDays(1), monthly.get().getIntervalId(), null, null);
    }

    @Scheduled(cron = "0 1 0 1 * ?")
    @Transactional(readOnly = false)
    public void computeQuarterlyAwards() {
        LocalDate now = LocalDate.now();
        int month = now.getMonthValue();
        if (month == 1 || month == 4 || month == 7 || month == 10) {
            System.out.println("=== QUARTERLY AWARD CRON ===");
            Optional<VotingInterval> quarterly = votingIntervalRepository.findByName("Quarterly");
            if (quarterly.isEmpty()) return;
            computeAwardsInternal(now.minusDays(1), quarterly.get().getIntervalId(), null, null);
        }
    }

    @Scheduled(cron = "0 1 0 1 * ?")
    @Transactional(readOnly = false)
    public void computeMidtermAwards() {
        LocalDate now = LocalDate.now();
        int month = now.getMonthValue();
        if (month == 1 || month == 7) {
            System.out.println("=== MIDTERM AWARD CRON ===");
            Optional<VotingInterval> midterm = votingIntervalRepository.findByName("Midterm");
            if (midterm.isEmpty()) return;
            computeAwardsInternal(now.minusDays(1), midterm.get().getIntervalId(), null, null);
        }
    }

    @Scheduled(cron = "0 1 0 1 1 ?")
    @Transactional(readOnly = false)
    public void computeAnnualAwards() {
        System.out.println("=== ANNUAL AWARD CRON ===");
        Optional<VotingInterval> annual = votingIntervalRepository.findByName("Annual");
        if (annual.isEmpty()) return;
        computeAwardsInternal(LocalDate.now().minusDays(1), annual.get().getIntervalId(), null, null);
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
        int voteCount;
        int score;
        LocalDateTime seniority;
    }

    private static class WinnerResult {
        UUID targetId;
        int voteCount;
        int score;
        LocalDateTime seniority;
        String determinationMethod;
        int tiedCandidatesCount;
    }
}