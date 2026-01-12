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

    @Cacheable(value = "awards", key = "'past-' + #type + '-' + #startDate + '-' + #endDate + '-' + #jurisdictionId + '-' + #genreId + '-' + #intervalId")
    public List<Award> getPastAwards(String type, LocalDate startDate, LocalDate endDate, 
                                      UUID jurisdictionId, UUID genreId, UUID intervalId) {
        // Use provided interval or determine from date range
        if (intervalId == null) {
            Optional<VotingInterval> intervalOpt = determineIntervalFromDateRange(startDate, endDate);
            intervalId = intervalOpt.map(VotingInterval::getIntervalId).orElse(null);
        }
        
        // Fetch existing awards
        List<Award> awards = awardRepository.findByFilters(type, jurisdictionId, genreId, intervalId, startDate, endDate);
        
        // Auto-populate if empty
        if (awards.isEmpty() && autoPopulateAwards && intervalId != null) {
            System.out.println("Auto-computing awards for " + type + ", date " + endDate);
            computeAwardsForDate(endDate, intervalId, jurisdictionId, genreId);
            awards = awardRepository.findByFilters(type, jurisdictionId, genreId, intervalId, startDate, endDate);
        }
        
        // Fallback if still empty
        if (awards.isEmpty() && intervalId != null) {
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
    // CORE AWARD COMPUTATION - WITH TIEBREAKERS
    // =========================================================================

    /**
     * Compute awards for a specific date with THREE-TIER TIEBREAKER:
     * 1. Most votes wins
     * 2. If tied on votes → highest score wins
     * 3. If tied on score → oldest account/song wins (seniority)
     * 
     * Creates exactly ONE winner per category/jurisdiction/genre/interval/date.
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
                // Compute SONG award
                computeSingleWinnerAward("song", jurId, genId, intervalId, startDate, awardDate, interval);
                
                // Compute ARTIST award
                computeSingleWinnerAward("artist", jurId, genId, intervalId, startDate, awardDate, interval);
            }
        }
    }

    /**
     * Core method: Compute a SINGLE winner for one category/jurisdiction/genre/interval/date.
     * Implements three-tier tiebreaker: votes → score → seniority
     */
    private void computeSingleWinnerAward(String targetType, UUID jurisdictionId, UUID genreId,
                                           UUID intervalId, LocalDate startDate, LocalDate awardDate,
                                           VotingInterval interval) {
        
        // Check if award already exists
        Long existingCount = awardRepository.existsByTargetTypeAndTargetIdAndJurisdictionIdAndIntervalIdAndAwardDate(
            targetType, null, jurisdictionId, intervalId, awardDate);
        
        // We need a different check - by targetType + jurisdiction + interval + date (not targetId)
        if (awardRepository.existsAwardForCategory(targetType, jurisdictionId, genreId, intervalId, awardDate)) {
            System.out.println("Award already exists for " + targetType + " in jurisdiction " + jurisdictionId + " on " + awardDate);
            return;
        }

        // Get all candidates with votes, ordered by vote count
        List<CandidateResult> candidates = getCandidatesWithVotes(targetType, jurisdictionId, genreId, intervalId, startDate, awardDate);

        if (candidates.isEmpty()) {
            System.out.println("No votes found for " + targetType + " in jurisdiction " + jurisdictionId + " on " + awardDate);
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

        // Create the award
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
            // NEW: Tiebreaker audit fields
            .determinationMethod(winner.determinationMethod)
            .winnerSeniority(winner.seniority)
            .tiedCandidatesCount(winner.tiedCandidatesCount)
            .build();

        awardRepository.save(award);
        
        // Award points to winner
        scoreUpdateService.onAward(winner.targetId, awardPoints);

        System.out.println("✓ Award created: " + targetType + " winner " + winner.targetId + 
                          " with " + winner.voteCount + " votes (determined by " + winner.determinationMethod + ")");
    }

    /**
     * Get all candidates with their vote counts for a given category/jurisdiction/genre/interval/date range.
     */
    @SuppressWarnings("unchecked")
    private List<CandidateResult> getCandidatesWithVotes(String targetType, UUID jurisdictionId, UUID genreId,
                                                          UUID intervalId, LocalDate startDate, LocalDate endDate) {
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
                  AND v.jurisdiction_id = :jurisdictionId
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
                  AND v.jurisdiction_id = :jurisdictionId
                  AND v.genre_id = :genreId
                  AND v.interval_id = :intervalId
                  AND v.vote_date BETWEEN :startDate AND :endDate
                  AND u.deleted_at IS NULL
                GROUP BY v.target_id, u.score, u.created_at
                ORDER BY vote_count DESC, score DESC, seniority ASC
            """;
        }

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("jurisdictionId", jurisdictionId);
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
     * Daily awards - runs at 12:01 AM (not midnight to avoid race conditions)
     * Computes awards for YESTERDAY's votes
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

        computeAwardsForDate(yesterday, dailyInterval.get().getIntervalId(), null, null);
        
        // Reset plays_today for the new day
        songRepository.resetPlaysToday(LocalDate.now());
    }

    /**
     * Weekly awards - Monday 12:01 AM
     */
    @Scheduled(cron = "0 1 0 * * MON")
    public void computeWeeklyAwards() {
        System.out.println("=== WEEKLY AWARD CRON ===");
        Optional<VotingInterval> weekly = votingIntervalRepository.findByName("Weekly");
        if (weekly.isEmpty()) return;
        computeAwardsForDate(LocalDate.now().minusDays(1), weekly.get().getIntervalId(), null, null);
    }

    /**
     * Monthly awards - 1st of month 12:01 AM
     */
    @Scheduled(cron = "0 1 0 1 * ?")
    public void computeMonthlyAwards() {
        System.out.println("=== MONTHLY AWARD CRON ===");
        Optional<VotingInterval> monthly = votingIntervalRepository.findByName("Monthly");
        if (monthly.isEmpty()) return;
        computeAwardsForDate(LocalDate.now().minusDays(1), monthly.get().getIntervalId(), null, null);
    }

    /**
     * Quarterly awards - Jan/Apr/Jul/Oct 1st 12:01 AM
     */
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

    /**
     * Midterm awards - Jan/Jul 1st 12:01 AM
     */
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

    /**
     * Annual awards - Jan 1st 12:01 AM
     */
    @Scheduled(cron = "0 1 0 1 1 ?")
    public void computeAnnualAwards() {
        System.out.println("=== ANNUAL AWARD CRON ===");
        Optional<VotingInterval> annual = votingIntervalRepository.findByName("Annual");
        if (annual.isEmpty()) return;
        computeAwardsForDate(LocalDate.now().minusDays(1), annual.get().getIntervalId(), null, null);
    }

    // =========================================================================
    // MANUAL COMPUTATION (for testing and retroactive awards)
    // =========================================================================

    /**
     * Manually compute awards for a specific date (for testing or retroactive computation)
     */
    @CacheEvict(value = {"awards", "leaderboards"}, allEntries = true)
    public void computeForInterval(UUID intervalId, UUID jurisdictionId, UUID genreId, LocalDate cronDate) {
        computeAwardsForDate(cronDate, intervalId, jurisdictionId, genreId);
    }

    /**
     * Manually trigger daily computation for a specific date (for testing)
     */
    public void computeDailyAwardsForDate(LocalDate cronDate) {
        Optional<VotingInterval> dailyInterval = votingIntervalRepository.findByName("Daily");
        if (dailyInterval.isEmpty()) return;
        computeAwardsForDate(cronDate, dailyInterval.get().getIntervalId(), null, null);
    }

    /**
     * Recompute ALL historical awards from votes data.
     * Use with caution - will create awards for all dates with votes.
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

    private List<Award> createFallbackAwards(String type, UUID jurisdictionId, UUID intervalId, LocalDate awardDate) {
        List<Award> fallbackAwards = new ArrayList<>();
        
        if ("song".equals(type)) {
            List<Song> songs = songRepository.findAll().stream()
                .filter(s -> s.getJurisdiction() != null && s.getJurisdiction().getJurisdictionId().equals(jurisdictionId))
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                .limit(10)
                .collect(Collectors.toList());
            
            for (Song song : songs) {
                Award award = Award.builder()
                    .targetType("song")
                    .targetId(song.getSongId())
                    .genre(song.getGenre())
                    .jurisdiction(song.getJurisdiction())
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
                .filter(u -> u.getDeletedAt() == null)  // Exclude soft-deleted users
                .filter(u -> u.getJurisdiction() != null && u.getJurisdiction().getJurisdictionId().equals(jurisdictionId))
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                .limit(10)
                .collect(Collectors.toList());
            
            for (User artist : artists) {
                Award award = Award.builder()
                    .targetType("artist")
                    .targetId(artist.getUserId())
                    .genre(artist.getGenre())
                    .jurisdiction(artist.getJurisdiction())
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
        String determinationMethod;  // "VOTES", "SCORE", or "SENIORITY"
        int tiedCandidatesCount;
    }
}