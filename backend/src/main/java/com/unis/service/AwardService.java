package com.unis.service;

import com.unis.entity.Award;
import com.unis.entity.VotingInterval;
import com.unis.repository.AwardRepository;
import com.unis.repository.VoteRepository;
import com.unis.repository.VotingIntervalRepository;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.GenreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private ScoreUpdateService scoreUpdateService;

    

    // Get current leaderboards (page 4; top by votes for period)
    public List<Award> getLeaderboards(String type, UUID intervalId, UUID jurisdictionId) {
        LocalDate start = LocalDate.now().minusDays(30);  // Last 30 days
        LocalDate end = LocalDate.now();
        return awardRepository.findTopByPeriod(jurisdictionId, intervalId, start, end);
    }

    // Get past awards/milestones (page 5; top for date range)
    public List<Award> getPastAwards(String type, LocalDate startDate, LocalDate endDate, UUID jurisdictionId, UUID genreId) {
    List<Award> awards = awardRepository.findTopByPeriod(jurisdictionId, null, startDate, endDate);
    if (genreId != null) {
        awards = awards.stream().filter(a -> a.getGenre().getGenreId().equals(genreId)).toList();
    }
    return awards;
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
                    if (voteCount > 0) {
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
                List<Object[]> topArtistVotes = voteRepository.findTopArtistVoteCountsForDate(jurisdictionId, dailyId, cronDate);  // Add repo method
                for (Object[] top : topArtistVotes) {
                UUID targetId = (UUID) top[0];
                int voteCount = ((Number) top[1]).intValue();
                if (voteCount > 0) {
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



        // Add multi-interval crons
        @Scheduled(cron = "0 1 0 * * MON")  // Weekly Monday 12:01 AM
        public void computeWeeklyAwards() {
        Optional<VotingInterval> weekly = votingIntervalRepository.findByName("Weekly");
        if (weekly.isEmpty()) return;
        computeForInterval(weekly.get().getIntervalId(), LocalDate.now());
        }

        @Scheduled(cron = "0 1 0 1 * ?")  // Monthly day 1
        public void computeMonthlyAwards() {
        Optional<VotingInterval> monthly = votingIntervalRepository.findByName("Monthly");
        if (monthly.isEmpty()) return;
        computeForInterval(monthly.get().getIntervalId(), LocalDate.now());
        }

        // General computeForInterval (dynamic range, mirror daily for all)
        public void computeForInterval(UUID intervalId, LocalDate cronDate) {
        LocalDate startDate = getIntervalStartDate(intervalId, cronDate);
        List<UUID> jurisdictions = jurisdictionRepository.findAllJurisdictionIds();
        List<UUID> genres = genreRepository.findAllGenreIds();

        for (UUID jurisdictionId : jurisdictions) {
            for (UUID genreId : genres) {
            // Top by votes for songs (filter for range)
            List<Object[]> topSongVotes = voteRepository.findTopVoteCountsForRange(jurisdictionId, intervalId, startDate, cronDate);
            for (Object[] top : topSongVotes) {
                UUID targetId = (UUID) top[0];
                int voteCount = ((Number) top[1]).intValue();
                if (voteCount > 0) {
                Award award = Award.builder()
                    .targetType("song")
                    .targetId(targetId)
                    .genre(genreRepository.findById(genreId).orElse(null))
                    .jurisdiction(jurisdictionRepository.findById(jurisdictionId).orElse(null))
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
            List<Object[]> topArtistVotes = voteRepository.findTopArtistVoteCountsForRange(jurisdictionId, intervalId, startDate, cronDate);
            for (Object[] top : topArtistVotes) {
                UUID targetId = (UUID) top[0];
                int voteCount = ((Number) top[1]).intValue();
                if (voteCount > 0) {
                Award award = Award.builder()
                    .targetType("artist")
                    .targetId(targetId)
                    .genre(genreRepository.findById(genreId).orElse(null))
                    .jurisdiction(jurisdictionRepository.findById(jurisdictionId).orElse(null))
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

        // Extend getIntervalStartDate for past date
        private LocalDate getIntervalStartDate(UUID intervalId, LocalDate cronDate) {
        VotingInterval interval = votingIntervalRepository.findById(intervalId).orElseThrow();
        
        switch (interval.getName()) {
            case "Daily":
                return cronDate.minusDays(1);  // Yesterday to cronDate
            case "Weekly":
                return cronDate.with(DayOfWeek.MONDAY);  // Monday of cronDate week
            case "Monthly":
                return cronDate.withDayOfMonth(1);  // 1st of cronDate month
            case "Quarterly":
                int currentQuarter = (cronDate.getMonthValue() - 1) / 3;
                return cronDate.withMonth(currentQuarter * 3 + 1).withDayOfMonth(1);  // 1st of quarter
            case "Midterm":
                int month = cronDate.getMonthValue();
                if (month >= 7) {
                    return cronDate.withMonth(7).withDayOfMonth(1);  // Jul 1 for second half
                } else {
                    return cronDate.withMonth(1).withDayOfMonth(1);  // Jan 1 for first half
                }
            case "Annual":
                return cronDate.withDayOfYear(1);  // Jan 1 of cronDate year
            default:
                return cronDate;
        }
    }

}