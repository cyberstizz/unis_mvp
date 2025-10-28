package com.unis.service;

import com.unis.entity.Vote;
import com.unis.entity.VotingInterval;
import com.unis.entity.Award;
import com.unis.repository.VoteRepository;
import com.unis.repository.AwardRepository;
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
import java.util.stream.Collectors;

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

    // Submit vote (page 2; checks unique, updates scores, increments awards—songs/artists only)
    public Vote submitVote(Vote vote) {
        if (vote.getUser() == null) {
            throw new RuntimeException("User must be set on Vote");
        }
        // Check unique constraint (songs/artists only)
        Long existingCount = voteRepository.existsByUserUserIdAndTargetTypeAndTargetIdAndGenreGenreIdAndJurisdictionJurisdictionIdAndIntervalIntervalIdAndVoteDate(
                vote.getUser().getUserId(), vote.getTargetType(), vote.getTargetId(), vote.getGenre().getGenreId(),
                vote.getJurisdiction().getJurisdictionId(), vote.getInterval().getIntervalId(), vote.getVoteDate());
        if (existingCount > 0) {
            throw new RuntimeException("Vote already exists for this user/period");
        }

        Vote saved = voteRepository.save(vote);

        // Update scores: +2 to voter, +3 to target (songs/artists only)
        scoreUpdateService.onVote(vote.getUser().getUserId(), vote.getTargetId(), vote.getTargetType());

        // Increment ongoing award if matching
        awardRepository.incrementAwardEngagement(vote.getTargetType(), vote.getTargetId(), vote.getJurisdiction().getJurisdictionId(), vote.getInterval().getIntervalId());

        return saved;
    }

    // Get vote results for page (page 2 cards; totals by target—songs/artists only)
    public Long getTotalVotesForTarget(String targetType, UUID targetId) {
        if (!"song".equals(targetType) && !"artist".equals(targetType)) {
            throw new IllegalArgumentException("Invalid targetType: " + targetType + " (song or artist only)");
        }
        return voteRepository.countByTarget(targetType, targetId);
    }

    // Get votes cast by user (for score: +2 each)
    public Long getVotesCastByUser(UUID userId) {
        return voteRepository.countByUserId(userId);
    }

    // Find votes by jurisdiction/genre/interval (page 2 results—songs/artists only)
    public List<Vote> getVotesByJurisdictionGenreInterval(UUID jurisdictionId, UUID genreId, UUID intervalId) {
        List<Vote> votes = voteRepository.findByJurisdictionGenreInterval(jurisdictionId, genreId, intervalId);
        return votes.stream().filter(v -> "song".equals(v.getTargetType()) || "artist".equals(v.getTargetType())).collect(Collectors.toList());
    }

    // Daily awards cron (midnight; top by votes for songs/artists per jurisdiction/genre/interval)
    @Scheduled(cron = "0 0 0 * * ?")
    public void computeDailyAwards() {
        Optional<VotingInterval> dailyInterval = votingIntervalRepository.findByName("Daily");
        if (dailyInterval.isEmpty()) return;

        UUID dailyId = dailyInterval.get().getIntervalId();
        List<UUID> jurisdictions = jurisdictionRepository.findAllJurisdictionIds();
        List<UUID> genres = genreRepository.findAllGenreIds();

        for (UUID jurisdictionId : jurisdictions) {
            for (UUID genreId : genres) {
                // Top by votes for songs
                List<Object[]> topSongVotes = voteRepository.findTopVoteCounts(jurisdictionId, dailyId);
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
                            .awardDate(LocalDate.now())
                            .votesCount(voteCount)
                            .engagementScore(voteCount * 10)
                            .weight(100)
                            .build();
                        awardRepository.save(award);
                        scoreUpdateService.onAward(targetId, 100);
                    }
                }
                // Mirror for artists (add findTopArtistVotes if needed)
            }
        }
    }
}