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

    // Playlist rewards. Public so PlaylistService takes back exactly what was
    // paid when a playlist is deleted — the two can never drift apart.
    public static final int COMMUNITY_PLAYLIST_POINTS = 5;
    public static final int FOLLOWER_MILESTONE = 10;          // followers needed
    public static final int FOLLOWER_MILESTONE_POINTS = 10;   // points paid, once per playlist
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

    @Autowired
    private com.unis.repository.VideoRepository videoRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // Points an artist gets when someone picks them to support.
    public static final int SUPPORTER_POINTS = 5;

    // ============================================================================
    // ONCE-EVER REWARDS (point_rewards table, see backend/sql/V2026_10c)
    // ============================================================================

    /**
     * Records that a one-time reward is being paid. Returns true the first time
     * this user claims this key, false every time after — so "like, unlike,
     * like" or "switch away and back" can't pay twice. The primary key makes
     * this safe even when two requests arrive at the same moment.
     *
     * Keys: like:song:<id>, like:video:<id>, supporter:<listenerId>,
     * referral:<referredUserId>, suggestion-vote:<playlist>:<song>,
     * suggestion-approved:<playlist>:<song>.
     */
    @Transactional
    public boolean claimOnce(UUID userId, String rewardKey, int points) {
        int inserted = jdbcTemplate.update(
            "INSERT INTO point_rewards (user_id, reward_key, points, created_at) "
                + "VALUES (?, ?, ?, NOW()) ON CONFLICT DO NOTHING",
            userId, rewardKey, points);
        return inserted == 1;
    }

    /**
     * Takes back a one-time reward this account gave someone else, used when an
     * account is deleted within 30 days of joining (UserService). Removes the
     * record and subtracts exactly the points it paid. Returns how many rewards
     * were taken back.
     */
    @Transactional
    public int takeBackRewardsFor(String rewardKey) {
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
            "DELETE FROM point_rewards WHERE reward_key = ? RETURNING user_id, points", rewardKey);
        for (java.util.Map<String, Object> row : rows) {
            UUID beneficiary = (UUID) row.get("user_id");
            int points = ((Number) row.get("points")).intValue();
            if (points > 0) {
                updateUserScoreIncrement(beneficiary, -points);
            }
        }
        return rows.size();
    }

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
        } else if ("video".equals(type)) {
            // Parity with computeVideoScores(): video plays are weighted x1
            videoRepository.incrementScore(mediaId, 1);
            videoRepository.findById(mediaId).ifPresent(video -> {
                if (video.getArtist() != null) {
                    updateUserScoreIncrement(video.getArtist().getUserId(), 1);
                }
            });
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

    /**
     * Video like scoring — same weights as songs (User +1, Video +2, Artist +1).
     * Matches computeVideoScores(), which weights video likes x2.
     */
    @Transactional
    public void onLikeVideo(UUID userId, UUID videoId) {
        updateUserScoreIncrement(userId, 1);
        videoRepository.incrementScore(videoId, 2);
        videoRepository.findById(videoId).ifPresent(video -> {
            if (video.getArtist() != null) {
                updateUserScoreIncrement(video.getArtist().getUserId(), 1);
            }
        });
    }

    /**
     * +5 to an artist when a fan picks them to support — once per fan, ever.
     * Before, a fan switching away and back paid the artist again each time.
     */
    @Transactional
    public void onSupporterAdded(UUID artistId, UUID listenerId) {
        if (claimOnce(artistId, "supporter:" + listenerId, SUPPORTER_POINTS)) {
            updateUserScoreIncrement(artistId, SUPPORTER_POINTS);
        }
    }

    /**
     * +5 to a listener (+2 to an artist) for referring someone. Recorded in
     * point_rewards so it can be taken back if the referred account is deleted
     * within 30 days (UserService.deleteAccount).
     */
    @Transactional
    public void onReferral(UUID referrerId, UUID referredUserId) {
        User referrer = userRepository.findById(referrerId).orElse(null);
        if (referrer != null) {
            int points = "artist".equals(referrer.getRole().toString()) ? 2 : 5;
            if (claimOnce(referrerId, "referral:" + referredUserId, points)) {
                updateUserScoreIncrement(referrerId, points);
            }
        }
    }

    @Transactional
    public void onAward(UUID targetId, int weight) {
        updateUserScoreIncrement(targetId, weight);
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
            updateUserScoreIncrement(userId, COMMUNITY_PLAYLIST_POINTS);
        }
    }

    /**
     * Pays the playlist owner the 10-follower bonus.
     *
     * PlaylistService decides WHEN: it calls this only after atomically flipping
     * the playlist's followerMilestoneAwarded flag, so the bonus is paid once per
     * playlist, ever. (Previously this paid whenever the count landed on exactly
     * 10, so an unfollow and re-follow at 9 paid it again every time.)
     */
    @Transactional
    public void onPlaylistFollowerMilestone(UUID creatorId) {
        updateUserScoreIncrement(creatorId, FOLLOWER_MILESTONE_POINTS);
    }

    /**
     * Called when an owner deletes their playlist. Takes back the points that
     * playlist earned them directly (see PlaylistService.pointsEarnedByOwner).
     * Without this, creating and deleting a community playlist paid +5 every time.
     */
    @Transactional
    public void onPlaylistDeleted(UUID ownerId, int pointsEarned) {
        if (pointsEarned > 0) {
            updateUserScoreIncrement(ownerId, -pointsEarned);
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

    // One statement for every eligible account. It used to load every user into
    // memory and then read and save each one (two queries per user, in one long
    // transaction): fine at 1,000 users, minutes of database time at 100,000.
    // Same rule: live accounts at least one full month old get +1 point.
    @Scheduled(cron = "0 0 0 1 * ?")
    @Transactional
    public void monthlyAgeBonuses() {
        int updated = jdbcTemplate.update("UPDATE users SET score = GREATEST(0, COALESCE(score, 0) + 1), level = "
                + LEVEL_SQL.replace("{s}", "GREATEST(0, COALESCE(score, 0) + 1)")
                + " WHERE deleted_at IS NULL AND created_at IS NOT NULL AND created_at <= ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusMonths(1)));
        System.out.println("[Score] action=monthly_age_bonus status=ok users=" + updated);
    }

    // ============================================================================
    // HELPERS
    // ============================================================================

    /**
     * Adds points in one atomic UPDATE. The old version read the user, added in
     * Java and saved the whole row: two plays credited to the same artist at the
     * same moment both read the same score and one point was lost, and saving
     * the whole row could put back a bio or photo another request had just
     * changed. The database now does the addition, so concurrent updates all
     * count. Floor at zero: taking points back must never leave a negative score.
     */
    private void updateUserScoreIncrement(UUID userId, int increment) {
        if (userId == null || increment == 0) return;
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbcTemplate).update(
                "UPDATE users SET score = GREATEST(0, COALESCE(score, 0) + :inc), level = "
                        + LEVEL_SQL.replace("{s}", "GREATEST(0, COALESCE(score, 0) + :inc)")
                        + " WHERE user_id = :userId",
                java.util.Map.of("inc", increment, "userId", userId));
    }

    /** The same thresholds as getLevel, for use inside SQL ({s} = the new score). */
    private static final String LEVEL_SQL = "CASE WHEN {s} >= 1000 THEN 'diamond' WHEN {s} >= 500 THEN 'platinum' "
            + "WHEN {s} >= 100 THEN 'gold' ELSE 'silver' END";

    private String getLevel(int score) {
        if (score >= 1000) return "diamond";
        if (score >= 500) return "platinum";
        if (score >= 100) return "gold";
        return "silver";
    }
}