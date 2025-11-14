package com.unis.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.Builder;
import lombok.Data;

import com.unis.entity.Song;
import com.unis.entity.User;
import com.unis.entity.Jurisdiction;
import com.unis.repository.UserRepository;
import java.time.DayOfWeek;
import com.unis.entity.Vote;
import com.unis.entity.VotingInterval;
import com.unis.dto.LeaderboardDto;
import com.unis.entity.Award;
import com.unis.repository.VoteRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.AwardRepository;
import com.unis.repository.VotingIntervalRepository;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.GenreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private SongRepository songRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private ScoreUpdateService scoreUpdateService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

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


        // Get nominees (artists or songs) ranked by vote count for a specific category
@SuppressWarnings("unchecked")
public List<?> getNominees(String targetType, UUID genreId, UUID jurisdictionId, UUID intervalId, int limit) {
    LocalDate startDate = getIntervalStartDate(intervalId);
    LocalDate endDate = LocalDate.now();
    List<UUID> jurisdictionIds = getJurisdictionHierarchy(jurisdictionId);
    
    // Temp log for debugging (remove later)
    System.out.println("getNominees params: targetType=" + targetType + ", genreId=" + genreId + 
                       ", jurisdictionIds=" + jurisdictionIds + ", interval=" + startDate + " to " + endDate + 
                       ", limit=" + limit);
    
    if ("artist".equalsIgnoreCase(targetType)) {
        // Get artist IDs with vote counts first
        String countQuery = """
        SELECT u.user_id, COALESCE(COUNT(v.vote_id), 0) as vote_count
        FROM users u
        LEFT JOIN votes v ON v.target_id = u.user_id 
            AND v.target_type = 'artist'
            AND v.genre_id = :genreId
            AND v.jurisdiction_id IN (:jurisdictionIds)
            AND v.interval_id = :intervalId
            AND v.vote_date BETWEEN :startDate AND :endDate
        WHERE u.role = 'artist'  
        AND u.genre_id = :genreId
        AND u.jurisdiction_id IN (:jurisdictionIds)  
        GROUP BY u.user_id
        ORDER BY vote_count DESC
        LIMIT :limit
    """;
        
        Query countQ = entityManager.createNativeQuery(countQuery);
        countQ.setParameter("genreId", genreId);
        countQ.setParameter("jurisdictionIds", jurisdictionIds);
        countQ.setParameter("intervalId", intervalId);
        countQ.setParameter("startDate", startDate);
        countQ.setParameter("endDate", endDate);
        countQ.setParameter("limit", limit);
        
        List<Object[]> results = countQ.getResultList();
        System.out.println("Artist query results count: " + results.size());  // Log: If 0, check DB seeding
        
        if (results.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Extract artist IDs in order
        List<UUID> artistIds = results.stream()
            .map(row -> UUID.fromString(row[0].toString()))
            .collect(Collectors.toList());
        
        // Fetch full User entities maintaining order
        List<User> artists = userRepository.findAllById(artistIds);
        
        // Sort to maintain vote count order (since findAllById doesn't guarantee order)
        Map<UUID, Integer> orderMap = new HashMap<>();
        for (int i = 0; i < artistIds.size(); i++) {
            orderMap.put(artistIds.get(i), i);
        }
        artists.sort(Comparator.comparingInt(a -> orderMap.getOrDefault(a.getUserId(), Integer.MAX_VALUE)));
        
        return artists;
        
    } else {
        // Same approach for songs
        String countQuery = """
            SELECT s.song_id, COALESCE(COUNT(v.vote_id), 0) as vote_count
            FROM songs s
            LEFT JOIN votes v ON v.target_id = s.song_id 
                AND v.target_type = 'song'
                AND v.genre_id = :genreId
                AND v.jurisdiction_id IN (:jurisdictionIds)
                AND v.interval_id = :intervalId
                AND v.vote_date BETWEEN :startDate AND :endDate
            WHERE s.genre_id = :genreId
              AND s.jurisdiction_id IN (:jurisdictionIds)
            GROUP BY s.song_id
            ORDER BY vote_count DESC
            LIMIT :limit
        """;
        
        Query countQ = entityManager.createNativeQuery(countQuery);
        countQ.setParameter("genreId", genreId);
        countQ.setParameter("jurisdictionIds", jurisdictionIds);
        countQ.setParameter("intervalId", intervalId);
        countQ.setParameter("startDate", startDate);
        countQ.setParameter("endDate", endDate);
        countQ.setParameter("limit", limit);
        
        List<Object[]> results = countQ.getResultList();
        System.out.println("Song query results count: " + results.size());  // Log: If 0, check DB seeding
        
        if (results.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<UUID> songIds = results.stream()
            .map(row -> UUID.fromString(row[0].toString()))
            .collect(Collectors.toList());
        
        List<Song> songs = songRepository.findAllById(songIds);
        
        // Sort to maintain vote count order
        Map<UUID, Integer> orderMap = new HashMap<>();
        for (int i = 0; i < songIds.size(); i++) {
            orderMap.put(songIds.get(i), i);
        }
        songs.sort(Comparator.comparingInt(s -> orderMap.getOrDefault(s.getSongId(), Integer.MAX_VALUE)));
        
        return songs;
    }
}

    // Helper: Get jurisdiction + all children (for parent jurisdiction votes)
    private List<UUID> getJurisdictionHierarchy(UUID jurisdictionId) {
        String query = """
            WITH RECURSIVE jurisdiction_hierarchy AS (
                SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
                UNION ALL
                SELECT j.jurisdiction_id FROM jurisdictions j
                INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
            )
            SELECT jurisdiction_id FROM jurisdiction_hierarchy
        """;
        
        Query q = entityManager.createNativeQuery(query);
        q.setParameter("jurisdictionId", jurisdictionId);
        
        @SuppressWarnings("unchecked")
        List<UUID> results = q.getResultList();
        return results;
    }

    // Helper: Get start date for interval
    private LocalDate getIntervalStartDate(UUID intervalId) {
        VotingInterval interval = votingIntervalRepository.findById(intervalId)
            .orElseThrow(() -> new RuntimeException("Interval not found"));
        
        LocalDate today = LocalDate.now();
        
        switch (interval.getName()) {
            case "Daily":
                return today.minusDays(1);  // Loosened for testing (include yesterday); revert to 'return today;' for prod
            case "Weekly":
                return today.with(DayOfWeek.MONDAY);
            case "Monthly":
                return today.withDayOfMonth(1);
            case "Quarterly":
                int currentQuarter = (today.getMonthValue() - 1) / 3;
                return today.withMonth(currentQuarter * 3 + 1).withDayOfMonth(1);
            case "Annual":
                return today.withDayOfYear(1);
            default:
                return today;
        }
    }

    // Check if user can vote in a jurisdiction
    public boolean canUserVoteInJurisdiction(UUID userId, UUID jurisdictionId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        Jurisdiction targetJurisdiction = jurisdictionRepository.findById(jurisdictionId)
            .orElseThrow(() -> new RuntimeException("Jurisdiction not found"));
        
        Jurisdiction userJurisdiction = user.getJurisdiction();
        
        // User can vote in their own jurisdiction
        if (userJurisdiction.getJurisdictionId().equals(jurisdictionId)) {
            return true;
        }
        
        // User can vote in parent jurisdiction
        if (userJurisdiction.getParentJurisdiction() != null && 
            userJurisdiction.getParentJurisdiction().getJurisdictionId().equals(jurisdictionId)) {
            return true;
        }
        
        // User can vote in top-level (sitewide) - check if target jurisdiction has no parent
        if (targetJurisdiction.getParentJurisdiction() == null) {
            return true;
        }
        
        return false;
    }

   // GET /v1/leaderboards - Live rankings (votes + plays, interval range, fallback top plays)
    @SuppressWarnings("unchecked")
    public List<LeaderboardDto> getLeaderboard(String targetType, UUID genreId, UUID jurisdictionId, UUID intervalId, int limit) {
        LocalDate startDate = getIntervalStartDate(intervalId);
        LocalDate endDate = LocalDate.now();
        List<UUID> jurisdictionIds = getJurisdictionHierarchy(jurisdictionId);

        if ("artist".equalsIgnoreCase(targetType)) {
            String query = """
            WITH RECURSIVE jurisdiction_hierarchy AS (
                SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
                UNION ALL
                SELECT j.jurisdiction_id FROM jurisdictions j INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
            )
            SELECT u.user_id, u.username, COALESCE(COUNT(v.vote_id), 0) + COALESCE(COUNT(sp.play_id), 0) as score, u.photo_url
            FROM users u LEFT JOIN votes v ON v.target_id = u.user_id AND v.target_type = 'artist' AND v.genre_id = :genreId AND v.jurisdiction_id IN (:jurisdictionIds) AND v.interval_id = :intervalId AND v.vote_date BETWEEN :startDate AND :endDate
            LEFT JOIN song_plays sp ON sp.song_id IN (SELECT s.song_id FROM songs s WHERE s.artist_id = u.user_id) AND DATE(sp.played_at) BETWEEN :startDate AND :endDate
            JOIN jurisdiction_hierarchy jh ON u.jurisdiction_id = jh.jurisdiction_id
            WHERE u.role = 'artist' AND u.genre_id = :genreId
            GROUP BY u.user_id, u.username, u.photo_url
            ORDER BY score DESC, COUNT(v.vote_id) DESC
            LIMIT :limit
            """;
            Query q = entityManager.createNativeQuery(query);
            q.setParameter("jurisdictionId", jurisdictionId);
            q.setParameter("genreId", genreId);
            q.setParameter("jurisdictionIds", jurisdictionIds);
            q.setParameter("intervalId", intervalId);
            q.setParameter("startDate", startDate);
            q.setParameter("endDate", endDate);
            q.setParameter("limit", limit);
            List<Object[]> results = q.getResultList();

            // Fallback if <5: Top by plays only (no :jurisdictionIds needed)
            if (results.size() < 5) {
            String fallbackQuery = """
                WITH RECURSIVE jurisdiction_hierarchy AS (
                SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
                UNION ALL
                SELECT j.jurisdiction_id FROM jurisdictions j INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
                )
                SELECT u.user_id, u.username, COALESCE(COUNT(sp.play_id), 0) as score, u.photo_url
                FROM users u LEFT JOIN song_plays sp ON sp.song_id IN (SELECT s.song_id FROM songs s WHERE s.artist_id = u.user_id) AND DATE(sp.played_at) BETWEEN :startDate AND :endDate
                JOIN jurisdiction_hierarchy jh ON u.jurisdiction_id = jh.jurisdiction_id
                WHERE u.role = 'artist' AND u.genre_id = :genreId
                GROUP BY u.user_id, u.username, u.photo_url
                ORDER BY score DESC
                LIMIT :fallbackLimit
            """;
            Query fq = entityManager.createNativeQuery(fallbackQuery);
            fq.setParameter("jurisdictionId", jurisdictionId);
            fq.setParameter("genreId", genreId);
            fq.setParameter("startDate", startDate);
            fq.setParameter("endDate", endDate);
            fq.setParameter("fallbackLimit", 5 - results.size());
            // No fq.setParameter("jurisdictionIds", jurisdictionIds); — not used
            List<Object[]> fallback = fq.getResultList();
            results.addAll(fallback);
            }

            // Normalize
            List<LeaderboardDto> leaderboard = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
            Object[] row = results.get(i);
            leaderboard.add(LeaderboardDto.builder()
                .rank(i + 1)
                .name(row[1].toString())
                .votes((Long) row[2])
                .artwork(row[3] != null ? row[3].toString() : null)
                .build());
            }
            return leaderboard;
        } else {  // song branch
            String query = """
            WITH RECURSIVE jurisdiction_hierarchy AS (
                SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
                UNION ALL
                SELECT j.jurisdiction_id FROM jurisdictions j INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
            )
            SELECT s.song_id, s.title, COALESCE(COUNT(v.vote_id), 0) + COALESCE(COUNT(sp.play_id), 0) as score, s.artwork_url, a.username as artist
            FROM songs s LEFT JOIN votes v ON v.target_id = s.song_id AND v.target_type = 'song' AND v.genre_id = :genreId AND v.jurisdiction_id IN (:jurisdictionIds) AND v.interval_id = :intervalId AND v.vote_date BETWEEN :startDate AND :endDate
            LEFT JOIN song_plays sp ON sp.song_id = s.song_id AND DATE(sp.played_at) BETWEEN :startDate AND :endDate
            INNER JOIN users a ON s.artist_id = a.user_id JOIN jurisdiction_hierarchy jh ON a.jurisdiction_id = jh.jurisdiction_id
            WHERE s.genre_id = :genreId
            GROUP BY s.song_id, s.title, s.artwork_url, a.username
            ORDER BY score DESC, COUNT(v.vote_id) DESC
            LIMIT :limit
            """;
            Query q = entityManager.createNativeQuery(query);
            q.setParameter("jurisdictionId", jurisdictionId);
            q.setParameter("genreId", genreId);
            q.setParameter("jurisdictionIds", jurisdictionIds);
            q.setParameter("intervalId", intervalId);
            q.setParameter("startDate", startDate);
            q.setParameter("endDate", endDate);
            q.setParameter("limit", limit);
            List<Object[]> results = q.getResultList();

            // Fallback if <5: Top by plays only
            if (results.size() < 5) {
            String fallbackQuery = """
                WITH RECURSIVE jurisdiction_hierarchy AS (
                SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
                UNION ALL
                SELECT j.jurisdiction_id FROM jurisdictions j INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
                )
                SELECT s.song_id, s.title, COALESCE(COUNT(sp.play_id), 0) as score, s.artwork_url, a.username as artist
                FROM songs s LEFT JOIN song_plays sp ON sp.song_id = s.song_id AND DATE(sp.played_at) BETWEEN :startDate AND :endDate
                INNER JOIN users a ON s.artist_id = a.user_id JOIN jurisdiction_hierarchy jh ON a.jurisdiction_id = jh.jurisdiction_id
                WHERE s.genre_id = :genreId
                GROUP BY s.song_id, s.title, s.artwork_url, a.username
                ORDER BY score DESC
                LIMIT :fallbackLimit
            """;
            Query fq = entityManager.createNativeQuery(fallbackQuery);
            fq.setParameter("jurisdictionId", jurisdictionId);
            fq.setParameter("genreId", genreId);
            fq.setParameter("startDate", startDate);
            fq.setParameter("endDate", endDate);
            fq.setParameter("fallbackLimit", 5 - results.size());
            // No fq.setParameter("jurisdictionIds", jurisdictionIds); — not used
            List<Object[]> fallback = fq.getResultList();
            results.addAll(fallback);
            }

            // Normalize
            List<LeaderboardDto> leaderboard = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
            Object[] row = results.get(i);
            leaderboard.add(LeaderboardDto.builder()
                .rank(i + 1)
                .name(row[1].toString())
                .votes((Long) row[2])
                .artwork(row[3] != null ? row[3].toString() : null)
                .artist(row[4] != null ? row[4].toString() : null)
                .build());
            }
            return leaderboard;
        }
    }

}

