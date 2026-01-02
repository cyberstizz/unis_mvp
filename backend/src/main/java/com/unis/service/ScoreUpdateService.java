package com.unis.service;

import com.unis.entity.User;
import com.unis.entity.Song;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class ScoreUpdateService {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private VoteRepository voteRepository;
    
    @Autowired
    private SongPlayRepository songPlayRepository;
    
    @Autowired
    private SupporterRepository supporterRepository;
    
    @Autowired
    private AwardRepository awardRepository;
    
    @Autowired
    private ReferralRepository referralRepository;

    // ============================================================================
    // EVENT-DRIVEN SCORING (Real-Time Updates)
    // ============================================================================

    /**
     * Called when a song/video is played
     * Awards points to: listener (+1), media (+1), and artist (+1 for their content being played)
     */
    @Transactional
    public void onPlay(UUID userId, UUID mediaId, String type) {
        // Listener gets +1 point for playing music
        updateUserScoreIncrement(userId, 1);
        
        if ("song".equals(type)) {
            // Song gets +1 point
            songRepository.incrementScore(mediaId, 1);
            
            // ✅ NEW: Artist gets +1 point for their song being played
            Song song = songRepository.findById(mediaId).orElse(null);
            if (song != null && song.getArtist() != null) {
                updateUserScoreIncrement(song.getArtist().getUserId(), 1);
            }
        }
        // Similar logic for videos if needed
    }

    /**
     * Called when a vote is cast
     * Awards points to: voter (+2), target (+3), and artist (+3 if song vote)
     */
    @Transactional
    public void onVote(UUID voterId, UUID targetId, String targetType) {
        // Voter gets +2 points
        updateUserScoreIncrement(voterId, 2);
        
        if ("artist".equals(targetType)) {
            // Artist being voted for gets +3 points
            updateUserScoreIncrement(targetId, 3);
            
        } else if ("song".equals(targetType)) {
            // Song gets +3 points
            songRepository.incrementScore(targetId, 3);
            
            // ✅ NEW: Artist (song creator) also gets +3 points for their song receiving a vote
            Song song = songRepository.findById(targetId).orElse(null);
            if (song != null && song.getArtist() != null) {
                updateUserScoreIncrement(song.getArtist().getUserId(), 3);
            }
        }
    }

    /**
     * Called when a user gains a supporter
     * Awards +5 points to the artist
     */
    @Transactional
    public void onSupporterAdded(UUID artistId) {
        updateUserScoreIncrement(artistId, 5);
    }

    /**
     * Called when a referral is created (new user signs up with referral code)
     * Awards +5 points to listener referrers, +2 points to artist referrers
     */
    @Transactional
    public void onReferral(UUID referrerId) {
        User referrer = userRepository.findById(referrerId).orElse(null);
        if (referrer != null) {
            // Listeners get +5, Artists get +2 (per your documentation)
            int points = "artist".equals(referrer.getRole().toString()) ? 2 : 5;
            updateUserScoreIncrement(referrerId, points);
        }
    }

    /**
     * Called when an award is won
     * Awards variable points based on award interval
     */
    @Transactional
    public void onAward(UUID targetId, int weight) {
        User target = userRepository.findById(targetId).orElse(null);
        if (target != null) {
            int newScore = target.getScore() + weight;
            String level = getLevel(newScore);
            target.setScore(newScore);
            target.setLevel(level);
            userRepository.save(target);
            System.out.println("Award score updated for " + target.getUsername() + ": +" + weight + " (new " + newScore + ")");
        } else {
            System.out.println("Target not found for award score update: " + targetId);
        }
    }

    // ============================================================================
    // MONTHLY SCHEDULED JOB (Account Age Bonus)
    // ============================================================================

    /**
     * Runs on the 1st of each month at midnight
     * Awards +1 point per month of account age to all users
     */
    @Scheduled(cron = "0 0 0 1 * ?")  // 1st day of each month at 00:00:00
    @Transactional
    public void monthlyAgeBonuses() {
        List<User> allUsers = userRepository.findAll();
        
        for (User user : allUsers) {
            LocalDateTime created = user.getCreatedAt();
            if (created != null) {
                // Calculate months since account creation
                long monthsOld = ChronoUnit.MONTHS.between(created, LocalDateTime.now());
                
                // Award +1 point per month (this is cumulative over account lifetime)
                // Note: This runs monthly, so each user gets +1 for being 1 month older
                if (monthsOld > 0) {
                    // Award just +1 per month (not cumulative monthsOld)
                    // The "account age" component accumulates naturally over time
                    updateUserScoreIncrement(user.getUserId(), 1);
                }
            }
        }
        
        System.out.println("Monthly age bonuses applied to " + allUsers.size() + " users");
    }

    // ============================================================================
    // BATCH JOBS (DISABLED - Commented Out)
    // ============================================================================

    // COMMENTED OUT - Using real-time scoring instead
    /*
    @Scheduled(fixedRate = 3600000)  // 1 hour
    @Transactional
    public void batchUpdateUserScores() {
        List<Object[]> scores = userRepository.computeUserScores();
        for (Object[] row : scores) {
            UUID userId = (UUID) row[0];
            int newScore = ((Number) row[1]).intValue();
            String level = getLevel(newScore);
            userRepository.updateUserScoreAndLevel(userId, newScore, level);
        }
    }

    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void batchUpdateArtistScores() {
        // Not implemented - using real-time scoring
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
    */

    // COMMENTED OUT - Awards are handled by VoteService.computeDailyAwards()
    /*
    @Scheduled(cron = "0 0 0 * * ?")
    @Transactional
    public void computeDailyAwards() {
        // For each jurisdiction/genre/interval (daily):
        // Top by votes/plays: awardRepository.findTopVoteCounts(jid, dailyIntervalId)
        // Insert Award, then +100 to winner score (daily weight)
        // Update level if threshold crossed
    }
    */

    // ============================================================================
    // HELPER METHODS
    // ============================================================================

    /**
     * Increment user score by a given amount
     */
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

    /**
     * Determine user/artist level based on score
     */
    private String getLevel(int score) {
        if (score >= 1000) return "diamond";
        if (score >= 500) return "platinum";
        if (score >= 100) return "gold";
        return "silver";
    }
}