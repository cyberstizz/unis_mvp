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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
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
            computeAwardsForDate(end, intervalId, jurisdictionId, null);
            awards = awardRepository.findTopByPeriod(jurisdictionId, intervalId, start, end);
        }
        
        if (awards.isEmpty()) {
            awards = createFallbackAwards(type, jurisdictionId, intervalId, end);
        }
        
        return populateAwardEntities(awards);
    }

    /**
     * Get past awards - AUTO-COMPUTES AND SAVES if none exist
     */
    @CacheEvict(value = "awards", allEntries = true)  // Evict since we might save new awards
    public List<Award> getPastAwards(String type, LocalDate startDate, LocalDate endDate, 
                                      UUID jurisdictionId, UUID genreId, UUID intervalId) {
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
        
        // AUTO-COMPUTE AND SAVE if no awards exist
        if (awards.isEmpty() && autoPopulateAwards) {
            System.out.println("No awards found - auto-computing for " + type + ", jurisdiction " + jurisdictionId + ", date " + endDate);
            
            // Compute awards (this SAVES them to database)
            computeAwardsForDate(endDate, intervalId, jurisdictionId, genreId);
            
            // Fetch the newly created awards
            awards = awardRepository.findByFilters(type, jurisdictionId, genreId, intervalId, startDate, endDate);
        }
        
        // If still empty after computation, create fallback (but don't save fallbacks)
        if (awards.isEmpty()) {
            System.out.println("Still no awards after computation - creating fallback display");
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

    // Overload for backward compatibility (without intervalId)
    public List<Award> getPastAwards(String type, LocalDate startDate, LocalDate endDate, 
                                      UUID jurisdictionId, UUID genreId) {
        return getPastAwards(type, startDate, endDate, jurisdictionId, genreId, null);
    }

    // =========================================================================
    // CORE AWARD COMPUTATION - WITH VOTE AGGREGATION FROM CHILDREN
    // =========================================================================

    /**
     * Compute awards for a specific date with:
     * - THREE-TIER TIEBREAKER: votes → score → seniority
     * - VOTE AGGREGATION: Parent jurisdictions include votes from all children
     * 
     * Creates exactly ONE winner per category/jurisdiction/genre/interval/date.
     * SAVES the award to the database permanently.
     */
    @CacheEvict(value = {"awards", "leaderboards"}, allEntries = true)
    public void computeAwardsForDate(LocalDate awardDate, UUID intervalId, UUID jurisdictionId, UUID genreId) {
        // Get jurisdictions to process (only voting-enabled ones)
        List<UUID> jurisdictions;
        if (jurisdictionId != null) {
            // Verify it's voting-enabled
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

        // Calculate date range for this interval
        LocalDate startDate = getIntervalStartDate(intervalId, awardDate);

        System.out.println("Computing awards for " + interval.getName() + " from " + startDate + " to " + awardDate);

        for (UUID jurId : jurisdictions) {
            for (UUID genId : genres) {
                // Compute SONG award (with vote aggregation from children)
                computeSingleWinnerAward("song", jurId, genId, intervalId, startDate, awardDate, interval);
                
                // Compute ARTIST award (with vote aggregation from children)
                computeSingleWinnerAward("artist", jurId, genId, intervalId, startDate, awardDate, interval);
            }
        }
    }

    /**
     * Core method: Compute a SINGLE winner for one category/jurisdiction/genre/interval/date.
     * 
     * KEY FEATURE: Aggregates votes from the jurisdiction AND all its children.
     * Example: Harlem award counts votes from Harlem + Uptown Harlem + Downtown Harlem
     */
    private void computeSingleWinnerAward(String targetType, UUID jurisdictionId, UUID genreId,
                                           UUID intervalId, LocalDate startDate, LocalDate awardDate,
                                           VotingInterval interval) {
        
        // Check if award already exists for this category
        if (awardRepository.existsAwardForCategory(targetType, jurisdictionId, genreId, intervalId, awardDate)) {
            System.out.println("Award already exists for " + targetType + " in jurisdiction " + jurisdictionId + " on " + awardDate);
            return;
        }

        // =====================================================================
        // KEY: Get all candidates with AGGREGATED votes from this jurisdiction
        // AND all its children
        // =====================================================================
        List<CandidateResult> candidates = getCandidatesWithAggregatedVotes(
            targetType, jurisdictionId, genreId, intervalId, startDate, awardDate
        );

        if (candidates.isEmpty()) {
            System.out.println("No votes found for " + targetType + " in jurisdiction " + jurisdictionId + " (including children) on " + awardDate);
            return;
        }

        // Apply three-tier tiebreaker to find THE winner
        WinnerResult winner = determineWinner(candidates, targetType);

        if (winner == null) {
            System.out.println("Could not determine winner for " + targetType);
            return;
        }

        // Get point value for this interval
        int awardPoints = AWARD_POINTS.getOrDefault(interval.getName(), 50);

        // Create and SAVE the award
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

        awardRepository.save(award);
        
        // Award points to winner
        scoreUpdateService.onAward(winner.targetId, awardPoints);

        System.out.println("✓ Award SAVED: " + targetType + " winner in " + jurisdictionId + 
                          " with " + winner.voteCount + " votes (determined by " + winner.determinationMethod + ")");
    }

    /**
     * Get all candidates with their vote counts AGGREGATED from the jurisdiction
     * AND ALL ITS CHILDREN.
     * 
     * This is the key method that enables:
     * - Downtown Harlem artist wins Downtown Harlem award
     * - Same artist ALSO wins Harlem award (if they have most votes across all of Harlem)
     */
    @SuppressWarnings("unchecked")
    private List<CandidateResult> getCandidatesWithAggregatedVotes(String targetType, UUID jurisdictionId, 
                                                                    UUID genreId, UUID intervalId, 
                                                                    LocalDate startDate, LocalDate endDate) {
        // =====================================================================
        // Get this jurisdiction and ALL its children using the hierarchy
        // =====================================================================
        List<UUID> jurisdictionIds = getJurisdictionAndAllChildren(jurisdictionId);
        
        System.out.println("Aggregating votes from " + jurisdictionIds.size() + " jurisdictions for " + jurisdictionId);

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
                WHERE v.target_type = 'song'
                  AND v.jurisdiction_id IN (:jurisdictionIds)
                  AND v.genre_id = :genreId
                  AND v.interval_id = :intervalId
                  AND v.vote_date BETWEEN :startDate AND :endDate
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
                  AND v.jurisdiction_id IN (:jurisdictionIds)
                  AND v.genre_id = :genreId
                  AND v.interval_id = :intervalId
                  AND v.vote_date BETWEEN :startDate AND :endDate
                  AND (u.deleted_at IS NULL)
                GROUP BY v.target_id, u.score, u.created_at
                ORDER BY vote_count DESC, score DESC, seniority ASC
            """;
        }

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("jurisdictionIds", jurisdictionIds);
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

        return candidates;
    }

    /**
     * Get a jurisdiction and ALL its descendants (children, grandchildren, etc.)
     * Uses recursive CTE to traverse the hierarchy.
     */
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
        
        List<UUID> results = query.getResultList();
        return results;
    }

    /**
     * Determine the winner using three-tier tiebreaker:
     * 1. Most votes
     * 2. Highest score (if tied on votes)
     * 3. Oldest account/song (if tied on score)
     */
    private WinnerResult determineWinner(List<CandidateResult> candidates, String targetType) {
        if (candidates.isEmpty()) {
            return null;
        }

        // Candidates are already sorted by votes DESC, score DESC, seniority ASC
        CandidateResult topCandidate = candidates.get(0);
        
        WinnerResult winner = new WinnerResult();
        winner.targetId = topCandidate.targetId;
        winner.voteCount = topCandidate.voteCount;
        winner.score = topCandidate.score;
        winner.seniority = topCandidate.seniority;

        // Count how many candidates are tied at each level
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

        // Determine how the winner was selected
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

    /**
     * Daily awards - runs at 12:01 AM
     * Computes awards for YESTERDAY's votes for ALL voting-enabled jurisdictions
     */
    @Scheduled(cron = "0 1 0 * * ?")  // 12:01 AM daily
    public void computeDailyAwards() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        System.out.println("=== DAILY AWARD CRON: Computing for " + yesterday + " ===");
        
        Optional<VotingInterval> dailyInterval = votingIntervalRepository.findByName("Daily");
        if (dailyInterval.isEmpty()) {
            System.out.println("ERROR: Daily interval not found!");
            return;
        }

        // Compute for ALL voting-enabled jurisdictions (null = all)
        computeAwardsForDate(yesterday, dailyInterval.get().getIntervalId(), null, null);
        
        // Reset plays_today for the new day
        songRepository.resetPlaysToday(LocalDate.now());
        
        System.out.println("=== DAILY AWARD CRON COMPLETE ===");
    }

    @Scheduled(cron = "0 1 0 * * MON")
    public void computeWeeklyAwards() {
        System.out.println("=== WEEKLY AWARD CRON ===");
        Optional<VotingInterval> weekly = votingIntervalRepository.findByName("Weekly");
        if (weekly.isEmpty()) return;
        computeAwardsForDate(LocalDate.now().minusDays(1), weekly.get().getIntervalId(), null, null);
    }

    @Scheduled(cron = "0 1 0 1 * ?")
    public void computeMonthlyAwards() {
        System.out.println("=== MONTHLY AWARD CRON ===");
        Optional<VotingInterval> monthly = votingIntervalRepository.findByName("Monthly");
        if (monthly.isEmpty()) return;
        computeAwardsForDate(LocalDate.now().minusDays(1), monthly.get().getIntervalId(), null, null);
    }

    @Scheduled(cron = "0 1 0 1 * ?")
    public void computeQuarterlyAwards() {
        LocalDate now = LocalDate.now();
        int month = now.getMonthValue();
        if (month == 1 || month == 4 || month == 7 || month == 10) {
            System.out.println("=== QUARTERLY AWARD CRON ===");
            Optional<VotingInterval> quarterly = votingIntervalRepository.findByName("Quarterly");
            if (quarterly.isEmpty()) return;
            computeAwardsForDate(now.minusDays(1), quarterly.get().getIntervalId(), null, null);
        }
    }

    @Scheduled(cron = "0 1 0 1 * ?")
    public void computeMidtermAwards() {
        LocalDate now = LocalDate.now();
        int month = now.getMonthValue();
        if (month == 1 || month == 7) {
            System.out.println("=== MIDTERM AWARD CRON ===");
            Optional<VotingInterval> midterm = votingIntervalRepository.findByName("Midterm");
            if (midterm.isEmpty()) return;
            computeAwardsForDate(now.minusDays(1), midterm.get().getIntervalId(), null, null);
        }
    }

    @Scheduled(cron = "0 1 0 1 1 ?")
    public void computeAnnualAwards() {
        System.out.println("=== ANNUAL AWARD CRON ===");
        Optional<VotingInterval> annual = votingIntervalRepository.findByName("Annual");
        if (annual.isEmpty()) return;
        computeAwardsForDate(LocalDate.now().minusDays(1), annual.get().getIntervalId(), null, null);
    }

    // =========================================================================
    // MANUAL COMPUTATION
    // =========================================================================

    @CacheEvict(value = {"awards", "leaderboards"}, allEntries = true)
    public void computeForInterval(UUID intervalId, UUID jurisdictionId, UUID genreId, LocalDate cronDate) {
        computeAwardsForDate(cronDate, intervalId, jurisdictionId, genreId);
    }

    public void computeDailyAwardsForDate(LocalDate cronDate) {
        Optional<VotingInterval> dailyInterval = votingIntervalRepository.findByName("Daily");
        if (dailyInterval.isEmpty()) return;
        computeAwardsForDate(cronDate, dailyInterval.get().getIntervalId(), null, null);
    }

    /**
     * Recompute ALL historical awards from votes data.
     */
    @CacheEvict(value = {"awards", "leaderboards"}, allEntries = true)
    public void recomputeAllHistoricalAwards() {
        System.out.println("=== RECOMPUTING ALL HISTORICAL AWARDS ===");
        
        // Get all distinct vote dates
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
            // Compute for ALL voting-enabled jurisdictions and ALL genres
            computeAwardsForDate(date, dailyInterval.get().getIntervalId(), null, null);
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

    /**
     * Create fallback awards for display when no votes exist.
     * These are NOT saved to the database.
     */
    private List<Award> createFallbackAwards(String type, UUID jurisdictionId, UUID intervalId, LocalDate awardDate) {
        List<Award> fallbackAwards = new ArrayList<>();
        
        // Get this jurisdiction and all children for fallback too
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

    // =========================================================================
    // INNER CLASSES FOR TIEBREAKER LOGIC
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