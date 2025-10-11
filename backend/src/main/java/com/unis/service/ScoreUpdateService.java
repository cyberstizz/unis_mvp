package com.unis.service;

import com.unis.entity.User;
import com.unis.repository.UserRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.ReferralRepository;
import com.unis.repository.VoteRepository;
import com.unis.repository.SongPlayRepository;
import com.unis.repository.SupporterRepository;
import com.unis.repository.AwardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class ScoreUpdateService {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SongRepository songRepository;

    // ... Autowire other repos (Vote, etc.)

    // Event-driven: On new play (call from MediaService)
    @Transactional
    public void onPlay(UUID userId, UUID mediaId, String type) {  // type 'song' or 'video'
        // +1 to listener (user score)
        updateUserScoreIncrement(userId, 1);
        // +1 to media (song/video score)
        if ("song".equals(type)) {
            songRepository.incrementScore(mediaId, 1);
        } // Similar for video
        // Artist gets +1 (join artist_id)
    }

    // Event-driven: On vote (call from VoteService)
    @Transactional
    public void onVote(UUID voterId, UUID targetId, String targetType) {
        // +2 to voter
        updateUserScoreIncrement(voterId, 2);
        // +3 to target (artist/song)
        if ("artist".equals(targetType)) {
            updateArtistScoreIncrement(targetId, 3);
        } else {
            // Song/Video: +3
        }
    }

    // Batch for age/referrals (hourly, not 10 min—lighter)
    @Scheduled(fixedRate = 3600000)  // 1 hour
    @Transactional
    public void batchUpdateUserScores() {
        List<Object[]> scores = userRepository.computeUserScores();
        for (Object[] row : scores) {
            UUID userId = (UUID) row[0];
            int newScore = ((Number) row[1]).intValue();
            String level = getLevel(newScore);  // Helper: silver <100, gold 100-499, etc.
            userRepository.updateUserScoreAndLevel(userId, newScore, level);
        }
    }

    // Similar batch for artists/songs (filter role='artist' in query)
    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void batchUpdateArtistScores() {
        // Query only artists, SUM(supporters *5 + votes_received *3 + plays *1 + awards *10)
        // Update score/level
    }

    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void batchUpdateSongScores() {
        List<Object[]> scores = songRepository.computeSongScores();
        for (Object[] row : scores) {
            UUID songId = (UUID) row[0];
            int newScore = ((Number) row[1]).intValue();
            String level = getLevel(newScore);
            songRepository.updateSongScoreAndLevel(songId, newScore, level);
        }
    }

    // Daily awards cron (light: aggregates only)
    @Scheduled(cron = "0 0 0 * * ?")
    @Transactional
    public void computeDailyAwards() {
        // For each jurisdiction/genre/interval (daily):
        // Top by votes/plays: awardRepository.findTopVoteCounts(jid, dailyIntervalId)
        // Insert Award, then +100 to winner score (daily weight)
        // Update level if threshold crossed
    }

    // Helpers
    private void updateUserScoreIncrement(UUID userId, int increment) {
        User user = userRepository.findById(userId).orElse(null);
        if (user != null) {
            int newScore = user.getScore() + increment;
            String level = getLevel(newScore);
            user.setScore(newScore);
            user.setLevel(level);
            userRepository.save(user);
        }
    }

    private void updateArtistScoreIncrement(UUID artistId, int increment) {
        User artist = userRepository.findById(artistId).orElse(null);
        if (artist != null) {
            int newScore = artist.getScore() + increment;
            String level = getLevel(newScore);
            artist.setScore(newScore);
            artist.setLevel(level);
            userRepository.save(artist);
        }
    }

    private String getLevel(int score) {
        if (score >= 1000) return "diamond";
        if (score >= 500) return "platinum";
        if (score >= 100) return "gold";
        return "silver";
    }

}