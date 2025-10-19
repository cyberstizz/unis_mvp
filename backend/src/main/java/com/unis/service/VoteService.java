package com.unis.service;

import com.unis.entity.Vote;
import com.unis.entity.Award;
import com.unis.repository.VoteRepository;
import com.unis.repository.AwardRepository;
import com.unis.repository.VotingIntervalRepository;  // For daily ID
import com.unis.repository.JurisdictionRepository;  // For all IDs
import com.unis.repository.GenreRepository;  // For all IDs
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class VoteService {
    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private AwardRepository awardRepository;

    @Autowired
    private VotingIntervalRepository votingIntervalRepository;

    @Autowired
    private JurisdictionRepository jurisdictionRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private ScoreUpdateService scoreUpdateService;

    // Submit vote (page 2; checks unique, updates scores, increments awards)
    public Vote submitVote(Vote vote) {
        // Check unique constraint
        if (voteRepository.existsByUserUserIdAndTargetTypeAndTargetIdAndGenreGenreIdAndJurisdictionJurisdictionIdAndIntervalIntervalIdAndVoteDate(
                vote.getUser().getUserId(), vote.getTargetType(), vote.getTargetId(), vote.getGenre().getGenreId(),
                vote.getJurisdiction().getJurisdictionId(), vote.getInterval().getIntervalId(), vote.getVoteDate())) {
            throw new RuntimeException("Vote already exists for this user/period");
        }

        Vote saved = voteRepository.save(vote);

        // Update scores: +2 to voter, +3 to target
        scoreUpdateService.onVote(vote.getUser().getUserId(), vote.getTargetId(), vote.getTargetType());

        // Increment ongoing award if matching
        awardRepository.incrementAwardEngagement(vote.getTargetType(), vote.getTargetId(), vote.getJurisdiction().getJurisdictionId(), vote.getInterval().getIntervalId());

        return saved;
    }

    // Get vote results for page (page 2 cards; totals by target)
    public Long getTotalVotesForTarget(String targetType, UUID targetId) {
        return voteRepository.countByTarget(targetType, targetId);
    }

    // Get votes cast by user (for score: +2 each)
    public Long getVotesCastByUser(UUID userId) {
        return voteRepository.countByUserId(userId);
    }

    // Find votes by jurisdiction/genre/interval (page 2 results)
    public List<Vote> getVotesByJurisdictionGenreInterval(UUID jurisdictionId, UUID genreId, UUID intervalId) {
        return voteRepository.findByJurisdictionGenreInterval(jurisdictionId, genreId, intervalId);
    }

    // Daily awards cron (midnight; top by votes/plays per jurisdiction/genre/interval)
    @Scheduled(cron = "0 0 0 * * ?")
    public void computeDailyAwards() {
        // For each jurisdiction/genre/interval (daily ID from voting_intervals)
        List<UUID> jurisdictions = jurisdictionRepository.findAll().stream().map(j -> j.getJurisdictionId()).toList();
        List<UUID> genres = genreRepository.findAll().stream().map(g -> g.getGenreId()).toList();
        UUID dailyIntervalId = votingIntervalRepository.findByName("Daily").get().getIntervalId();

        for (UUID jurisdictionId : jurisdictions) {
            for (UUID genreId : genres) {
                List<Object[]> topVotes = voteRepository.findTopVoteCounts(jurisdictionId, dailyIntervalId);
                for (Object[] top : topVotes) {
                    UUID targetId = (UUID) top[0];
                    int voteCount = ((Number) top[1]).intValue();
                    if (voteCount > 0) {
                        Award award = Award.builder()
                            .targetType("song")  // Or "artist"; extend for type
                            .targetId(targetId)
                            .genre(genreRepository.findById(genreId).orElse(null))
                            .jurisdiction(jurisdictionRepository.findById(jurisdictionId).orElse(null))
                            .interval(votingIntervalRepository.findById(dailyIntervalId).orElse(null))
                            .awardDate(LocalDate.now())
                            .votesCount(voteCount)
                            .engagementScore(voteCount * 10)  // Placeholder
                            .weight(100)  // Daily
                            .build();
                        awardRepository.save(award);
                        // Boost score +100 for daily award
                        scoreUpdateService.onAward(targetId, 100);  // Add method to score service if needed
                    }
                }
            }
        }
    }
}