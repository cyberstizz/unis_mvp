package com.unis.service;

import com.unis.entity.Award;
import com.unis.entity.VotingInterval;
import com.unis.repository.AwardRepository;
import com.unis.repository.VoteRepository;
import com.unis.repository.VotingIntervalRepository;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.GenreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
        LocalDate start = LocalDate.now().minusDays(30);  // Example: last 30 days
        LocalDate end = LocalDate.now();
        return awardRepository.findTopByPeriod(jurisdictionId, intervalId, start, end);
    }

    // Get past awards/milestones (page 5; top 10 for date range)
    public List<Award> getPastAwards(String type, LocalDate start, LocalDate end, UUID jurisdictionId) {
        return awardRepository.findTopByPeriod(jurisdictionId, null, start, end);
    }

    // Daily awards cron (midnight; top by votes per jurisdiction/genre/interval)
    @Scheduled(cron = "0 0 0 * * ?")
    public void computeDailyAwards() {
        Optional<VotingInterval> dailyInterval = votingIntervalRepository.findByName("Daily");
        if (dailyInterval.isEmpty()) return;

        UUID dailyId = dailyInterval.get().getIntervalId();
        List<UUID> jurisdictions = jurisdictionRepository.findAll().stream().map(j -> j.getJurisdictionId()).toList();
        List<UUID> genres = genreRepository.findAll().stream().map(g -> g.getGenreId()).toList();

        for (UUID jurisdictionId : jurisdictions) {
            for (UUID genreId : genres) {
                // Top by votes (example for songs; mirror for artists)
                List<Object[]> topVotes = voteRepository.findTopVoteCounts(jurisdictionId, dailyId);
                for (Object[] top : topVotes) {
                    UUID targetId = (UUID) top[0];
                    int voteCount = ((Number) top[1]).intValue();
                    if (voteCount > 0) {
                        Award award = Award.builder()
                            .targetType("song")  // Or "artist"; extend for type
                            .targetId(targetId)
                            .genre(genreRepository.findById(genreId).orElse(null))
                            .jurisdiction(jurisdictionRepository.findById(jurisdictionId).orElse(null))
                            .interval(votingIntervalRepository.findById(dailyId).orElse(null))
                            .awardDate(LocalDate.now())
                            .votesCount(voteCount)
                            .engagementScore(voteCount * 10)  // Placeholder
                            .weight(100)  // Daily
                            .build();
                        awardRepository.save(award);
                        // Boost score +100 for daily award
                        scoreUpdateService.onAward(targetId, 100);  // Add if needed
                    }
                }
            }
        }
    }
}