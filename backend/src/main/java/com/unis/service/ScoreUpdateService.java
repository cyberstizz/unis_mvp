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

    @Transactional
    public void onPlay(UUID userId, UUID mediaId, String type) {
        updateUserScoreIncrement(userId, 1);
        
        if ("song".equals(type)) {
            songRepository.incrementScore(mediaId, 1);
            Song song = songRepository.findById(mediaId).orElse(null);
            if (song != null && song.getArtist() != null) {
                updateUserScoreIncrement(song.getArtist().getUserId(), 1);
            }
        }
    }

    @Transactional
    public void onVote(UUID voterId, UUID targetId, String targetType) {
        updateUserScoreIncrement(voterId, 2);
        
        if ("artist".equals(targetType)) {
            updateUserScoreIncrement(targetId, 3);
        } else if ("song".equals(targetType)) {
            songRepository.incrementScore(targetId, 3);
            Song song = songRepository.findById(targetId).orElse(null);
            if (song != null && song.getArtist() != null) {
                updateUserScoreIncrement(song.getArtist().getUserId(), 3);
            }
        }
    }

    @Transactional
    public void onLike(UUID userId, UUID songId) {
        updateUserScoreIncrement(userId, 1);
        songRepository.incrementScore(songId, 2);
        Song song = songRepository.findById(songId).orElse(null);
        if (song != null && song.getArtist() != null) {
            updateUserScoreIncrement(song.getArtist().getUserId(), 1);
        }
    }

    @Transactional
    public void onSupporterAdded(UUID artistId) {
        updateUserScoreIncrement(artistId, 5);
    }

    @Transactional
    public void onReferral(UUID referrerId) {
        User referrer = userRepository.findById(referrerId).orElse(null);
        if (referrer != null) {
            int points = "artist".equals(referrer.getRole().toString()) ? 2 : 5;
            updateUserScoreIncrement(referrerId, points);
        }
    }

    @Transactional
    public void onAward(UUID targetId, int weight) {
        User target = userRepository.findById(targetId).orElse(null);
        if (target != null) {
            int newScore = target.getScore() + weight;
            String level = getLevel(newScore);
            target.setScore(newScore);
            target.setLevel(level);
            userRepository.save(target);
        }
    }

    // ============================================================================
    // PLAYLIST SCORING
    // ============================================================================

    /**
     * Called when a community playlist is created.
     * Awards +5 points to the creator for contributing to the community.
     * Personal playlists do not earn points.
     */
    @Transactional
    public void onPlaylistCreated(UUID userId, String playlistType) {
        if ("community".equals(playlistType)) {
            updateUserScoreIncrement(userId, 5);
        }
    }

    /**
     * Called when a playlist reaches a follower milestone.
     * Awards +10 points to the playlist creator when it hits 10 followers.
     * This should be called by PlaylistService after incrementing follower count.
     */
    @Transactional
    public void onPlaylistFollowerMilestone(UUID creatorId, int newFollowerCount) {
        if (newFollowerCount == 10) {
            updateUserScoreIncrement(creatorId, 10);
        }
    }

    /**
     * Called when a song suggestion is approved by community votes.
     * Awards +2 points to the user who suggested the song.
     */
    @Transactional
    public void onSongSuggestionApproved(UUID suggesterId) {
        updateUserScoreIncrement(suggesterId, 2);
    }

    /**
     * Called when a user casts a vote on a community playlist suggestion.
     * Awards +1 point for participating in community curation.
     */
    @Transactional
    public void onPlaylistVoteCast(UUID voterId) {
        updateUserScoreIncrement(voterId, 1);
    }

    // ============================================================================
    // MONTHLY SCHEDULED JOB
    // ============================================================================

    @Scheduled(cron = "0 0 0 1 * ?")
    @Transactional
    public void monthlyAgeBonuses() {
        List<User> allUsers = userRepository.findAll();
        
        for (User user : allUsers) {
            LocalDateTime created = user.getCreatedAt();
            if (created != null) {
                long monthsOld = ChronoUnit.MONTHS.between(created, LocalDateTime.now());
                if (monthsOld > 0) {
                    updateUserScoreIncrement(user.getUserId(), 1);
                }
            }
        }
    }

    // ============================================================================
    // HELPERS
    // ============================================================================

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

    private String getLevel(int score) {
        if (score >= 1000) return "diamond";
        if (score >= 500) return "platinum";
        if (score >= 100) return "gold";
        return "silver";
    }
}