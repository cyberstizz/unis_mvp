package com.unis.service;

import com.unis.entity.Award;
import com.unis.entity.Song;
import com.unis.entity.User;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

    @Value("${unis.auto-populate-awards:true}")
    private boolean autoPopulateAwards;

    // Get current leaderboards (page 4; top by votes for period)
    public List<Award> getLeaderboards(String type, UUID intervalId, UUID jurisdictionId) {
        // Default to Daily interval if null
        if (intervalId == null) {
            Optional<VotingInterval> dailyInterval = votingIntervalRepository.findByName("Daily");
            if (dailyInterval.isPresent()) {
                intervalId = dailyInterval.get().getIntervalId();
            } else {
                System.out.println("No Daily interval found - returning empty leaderboard");
                return new ArrayList<>();
            }
        }
        
        LocalDate start = LocalDate.now().minusDays(30);  // Last 30 days
        LocalDate end = LocalDate.now();
        List<Award> awards = awardRepository.findTopByPeriod(jurisdictionId, intervalId, start, end);
        
        if (awards.isEmpty() && autoPopulateAwards) {
            System.out.println("Auto-populating leaderboards for " + type + ", interval " + intervalId + ", jur " + jurisdictionId);
            computeForInterval(intervalId, jurisdictionId, null, LocalDate.now());
            awards = awardRepository.findTopByPeriod(jurisdictionId, intervalId, start, end);
        }
        
        // FALLBACK: If still empty (no votes/plays), create synthetic awards from existing content
        if (awards.isEmpty()) {
            System.out.println("No votes found - creating fallback awards from existing content");
            awards = createFallbackAwards(type, jurisdictionId, intervalId, end);
        }
        
        // Populate related entities
        return populateAwardEntities(awards);
    }

    // Get past awards/milestones (page 5; top for date range, genreId optional)
    public List<Award> getPastAwards(String type, LocalDate startDate, LocalDate endDate, UUID jurisdictionId, UUID genreId) {
        // Determine interval based on date range
        Optional<VotingInterval> intervalOpt = determineIntervalFromDateRange(startDate, endDate);
        UUID intervalId = intervalOpt.map(VotingInterval::getIntervalId).orElse(null);
        
        // Fetch existing awards
        List<Award> awards = awardRepository.findTopByPeriod(jurisdictionId, intervalId, startDate, endDate);
        
        // Filter by genre if provided
        if (genreId != null) {
            awards = awards.stream()
                .filter(a -> a.getGenre() != null && a.getGenre().getGenreId().equals(genreId))
                .collect(Collectors.toList());
        }
        
        // Auto-populate if empty
        if (awards.isEmpty() && autoPopulateAwards) {
            System.out.println("Auto-populating milestones for " + type + ", range " + startDate + " to " + endDate + ", jur " + jurisdictionId + ", genre " + genreId);
            
            if (intervalOpt.isEmpty()) {
                // Default to daily if no interval found
                intervalOpt = votingIntervalRepository.findByName("Daily");
            }
            
            if (intervalOpt.isPresent()) {
                computeForInterval(intervalOpt.get().getIntervalId(), jurisdictionId, genreId, endDate);
                awards = awardRepository.findTopByPeriod(jurisdictionId, intervalId, startDate, endDate);
                
                if (genreId != null) {
                    awards = awards.stream()
                        .filter(a -> a.getGenre() != null && a.getGenre().getGenreId().equals(genreId))
                        .collect(Collectors.toList());
                }
            }
        }
        
        // FALLBACK: If still empty (no votes/plays), create synthetic awards
        if (awards.isEmpty() && intervalOpt.isPresent()) {
            System.out.println("No votes found for past awards - creating fallback awards");
            awards = createFallbackAwards(type, jurisdictionId, intervalOpt.get().getIntervalId(), endDate);
            
            if (genreId != null) {
                awards = awards.stream()
                    .filter(a -> a.getGenre() != null && a.getGenre().getGenreId().equals(genreId))
                    .collect(Collectors.toList());
            }
        }
        
        // Populate related entities
        return populateAwardEntities(awards);
    }

    // FALLBACK: Create awards from existing songs/artists when no votes exist
    private List<Award> createFallbackAwards(String type, UUID jurisdictionId, UUID intervalId, LocalDate awardDate) {
        List<Award> fallbackAwards = new ArrayList<>();
        
        if ("song".equals(type)) {
            // Get songs in this jurisdiction, ordered by score (or just take first 10)
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
                    .votesCount(0)  // No votes, just placeholder
                    .engagementScore(song.getScore())
                    .weight(100)
                    .caption("Placeholder award - no votes cast yet")
                    .build();
                fallbackAwards.add(award);
            }
        } else if ("artist".equals(type)) {
            // Get artists in this jurisdiction
            List<User> artists = userRepository.findAll().stream()
                .filter(u -> u.getRole() == User.Role.artist)
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
                    .caption("Placeholder award - no votes cast yet")
                    .build();
                fallbackAwards.add(award);
            }
        }
        
        return fallbackAwards;
    }

    // Helper: Populate Song/User entities for frontend
    private List<Award> populateAwardEntities(List<Award> awards) {
        for (Award award : awards) {
            if ("song".equals(award.getTargetType())) {
                songRepository.findById(award.getTargetId()).ifPresent(song -> {
                    award.setSong(song);
                    // Force load artist for song (otherwise lazy load fails)
                    if (song.getArtist() != null) {
                        song.getArtist().getUsername(); // Trigger lazy load
                    }
                });
            } else if ("artist".equals(award.getTargetType())) {
                userRepository.findById(award.getTargetId()).ifPresent(award::setUser);
            }
        }
        return awards;
    }

    // Helper: Determine interval from date range
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

    // Daily awards cron (midnight; top by votes for songs/artists per jurisdiction/genre/interval)
    @Scheduled(cron = "0 0 0 * * ?")
    public void computeDailyAwards() {
        computeDailyAwardsForDate(LocalDate.now());
    }

    // Manual/past cron (for testing/retroactive; processes votes/plays for given date)
    public void computeDailyAwardsForDate(LocalDate cronDate) {
        Optional<VotingInterval> dailyInterval = votingIntervalRepository.findByName("Daily");
        if (dailyInterval.isEmpty()) return;

        UUID dailyId = dailyInterval.get().getIntervalId();
        List<UUID> jurisdictions = jurisdictionRepository.findAllJurisdictionIds();
        List<UUID> genres = genreRepository.findAllGenreIds();

        for (UUID jurisdictionId : jurisdictions) {
            for (UUID genreId : genres) {
                // Top by votes for songs (filter for date)
                List<Object[]> topSongVotes = voteRepository.findTopVoteCountsForDate(jurisdictionId, dailyId, cronDate);
                for (Object[] top : topSongVotes) {
                    UUID targetId = (UUID) top[0];
                    int voteCount = ((Number) top[1]).intValue();
                    if (voteCount > 0) {  // Only if votes exist
                        if (awardRepository.existsByTargetTypeAndTargetIdAndJurisdictionIdAndIntervalIdAndAwardDate("song", targetId, jurisdictionId, dailyId, cronDate) > 0) {
                            System.out.println("Skipping duplicate song award for target " + targetId + ", date " + cronDate);
                            continue;
                        }
                        System.out.println("Saving song award for target " + targetId + ", votes " + voteCount);
                        Award award = Award.builder()
                            .targetType("song")
                            .targetId(targetId)
                            .genre(genreRepository.findById(genreId).orElse(null))
                            .jurisdiction(jurisdictionRepository.findById(jurisdictionId).orElse(null))
                            .interval(votingIntervalRepository.findById(dailyId).orElse(null))
                            .awardDate(cronDate)
                            .votesCount(voteCount)
                            .engagementScore(voteCount * 10)
                            .weight(100)
                            .build();
                        awardRepository.save(award);
                        scoreUpdateService.onAward(targetId, 100);
                    }
                }
                
                // Top by votes for artists (filter for date)
                List<Object[]> topArtistVotes = voteRepository.findTopArtistVoteCountsForDate(jurisdictionId, dailyId, cronDate);
                for (Object[] top : topArtistVotes) {
                    UUID targetId = (UUID) top[0];
                    int voteCount = ((Number) top[1]).intValue();
                    if (voteCount > 0) {
                        if (awardRepository.existsByTargetTypeAndTargetIdAndJurisdictionIdAndIntervalIdAndAwardDate("artist", targetId, jurisdictionId, dailyId, cronDate) > 0) {
                            System.out.println("Skipping duplicate artist award for target " + targetId + ", date " + cronDate);
                            continue;
                        }
                        System.out.println("Saving artist award for target " + targetId + ", votes " + voteCount);
                        Award award = Award.builder()
                            .targetType("artist")
                            .targetId(targetId)
                            .genre(genreRepository.findById(genreId).orElse(null))
                            .jurisdiction(jurisdictionRepository.findById(jurisdictionId).orElse(null))
                            .interval(votingIntervalRepository.findById(dailyId).orElse(null))
                            .awardDate(cronDate)
                            .votesCount(voteCount)
                            .engagementScore(voteCount * 10)
                            .weight(100)
                            .build();
                        awardRepository.save(award);
                        scoreUpdateService.onAward(targetId, 100);
                    }
                }
            }
        }
    }

    // Multi-interval crons (call computeForInterval)
    @Scheduled(cron = "0 1 0 * * MON")  // Weekly Monday 12:01 AM
    public void computeWeeklyAwards() {
        Optional<VotingInterval> weekly = votingIntervalRepository.findByName("Weekly");
        if (weekly.isEmpty()) return;
        computeForInterval(weekly.get().getIntervalId(), null, null, LocalDate.now());
    }

    @Scheduled(cron = "0 1 0 1 * ?")  // Monthly day 1
    public void computeMonthlyAwards() {
        Optional<VotingInterval> monthly = votingIntervalRepository.findByName("Monthly");
        if (monthly.isEmpty()) return;
        computeForInterval(monthly.get().getIntervalId(), null, null, LocalDate.now());
    }

    @Scheduled(cron = "0 1 0 1 * ?")  // Quarterly Jan/Apr/Jul/Oct day 1
    public void computeQuarterlyAwards() {
        LocalDate now = LocalDate.now();
        int month = now.getMonthValue();
        // Only run in Jan, Apr, Jul, Oct
        if (month == 1 || month == 4 || month == 7 || month == 10) {
            Optional<VotingInterval> quarterly = votingIntervalRepository.findByName("Quarterly");
            if (quarterly.isEmpty()) return;
            computeForInterval(quarterly.get().getIntervalId(), null, null, now);
        }
    }

    @Scheduled(cron = "0 1 0 1 * ?")  // Midterm Jan 1, Jul 1
    public void computeMidtermAwards() {
        LocalDate now = LocalDate.now();
        int month = now.getMonthValue();
        // Only run in Jan and Jul
        if (month == 1 || month == 7) {
            Optional<VotingInterval> midterm = votingIntervalRepository.findByName("Midterm");
            if (midterm.isEmpty()) return;
            computeForInterval(midterm.get().getIntervalId(), null, null, now);
        }
    }

    @Scheduled(cron = "0 1 0 1 1 ?")  // Annual Jan 1
    public void computeAnnualAwards() {
        Optional<VotingInterval> annual = votingIntervalRepository.findByName("Annual");
        if (annual.isEmpty()) return;
        computeForInterval(annual.get().getIntervalId(), null, null, LocalDate.now());
    }

    // General computeForInterval (dynamic range, targeted jur/genre or all)
    public void computeForInterval(UUID intervalId, UUID jurisdictionId, UUID genreId, LocalDate cronDate) {
        LocalDate startDate = getIntervalStartDate(intervalId, cronDate);
        List<UUID> jurisdictions = jurisdictionId != null ? List.of(jurisdictionId) : jurisdictionRepository.findAllJurisdictionIds();
        List<UUID> genres = genreId != null ? List.of(genreId) : genreRepository.findAllGenreIds();

        for (UUID jurId : jurisdictions) {
            for (UUID genId : genres) {
                // Top by votes for songs (filter for range)
                List<Object[]> topSongVotes = voteRepository.findTopVoteCountsForRange(jurId, intervalId, startDate, cronDate);
                for (Object[] top : topSongVotes) {
                    UUID targetId = (UUID) top[0];
                    int voteCount = ((Number) top[1]).intValue();
                    if (voteCount > 0) {
                        if (awardRepository.existsByTargetTypeAndTargetIdAndJurisdictionIdAndIntervalIdAndAwardDate("song", targetId, jurId, intervalId, cronDate) > 0) {
                            System.out.println("Skipping duplicate song award for target " + targetId + ", date " + cronDate);
                            continue;
                        }
                        System.out.println("Saving song award for target " + targetId + ", votes " + voteCount + ", jur " + jurId + ", genre " + genId);
                        Award award = Award.builder()
                            .targetType("song")
                            .targetId(targetId)
                            .genre(genreRepository.findById(genId).orElse(null))
                            .jurisdiction(jurisdictionRepository.findById(jurId).orElse(null))
                            .interval(votingIntervalRepository.findById(intervalId).orElse(null))
                            .awardDate(cronDate)
                            .votesCount(voteCount)
                            .engagementScore(voteCount * 10)
                            .weight(100)
                            .build();
                        awardRepository.save(award);
                        scoreUpdateService.onAward(targetId, 100);
                    }
                }
                
                // Mirror for artists
                List<Object[]> topArtistVotes = voteRepository.findTopArtistVoteCountsForRange(jurId, intervalId, startDate, cronDate);
                for (Object[] top : topArtistVotes) {
                    UUID targetId = (UUID) top[0];
                    int voteCount = ((Number) top[1]).intValue();
                    if (voteCount > 0) {
                        if (awardRepository.existsByTargetTypeAndTargetIdAndJurisdictionIdAndIntervalIdAndAwardDate("artist", targetId, jurId, intervalId, cronDate) > 0) {
                            System.out.println("Skipping duplicate artist award for target " + targetId + ", date " + cronDate);
                            continue;
                        }
                        System.out.println("Saving artist award for target " + targetId + ", votes " + voteCount + ", jur " + jurId + ", genre " + genId);
                        Award award = Award.builder()
                            .targetType("artist")
                            .targetId(targetId)
                            .genre(genreRepository.findById(genId).orElse(null))
                            .jurisdiction(jurisdictionRepository.findById(jurId).orElse(null))
                            .interval(votingIntervalRepository.findById(intervalId).orElse(null))
                            .awardDate(cronDate)
                            .votesCount(voteCount)
                            .engagementScore(voteCount * 10)
                            .weight(100)
                            .build();
                        awardRepository.save(award);
                        scoreUpdateService.onAward(targetId, 100);
                    }
                }
            }
        }
    }

    // getIntervalStartDate (full, relative to cronDate)
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
}