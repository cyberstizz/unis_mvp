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
import com.unis.repository.SongPlayRepository;
import com.unis.repository.GenreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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
    private SongPlayRepository songPlayRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private ScoreUpdateService scoreUpdateService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    // =========================================================================
    // VOTE RULES
    //
    // Every date below is New York time — the same clock the controller stamps
    // vote_date with and the award cron closes periods on.
    // =========================================================================
    private static final java.time.ZoneId UNIS_ZONE = java.time.ZoneId.of("America/New_York");

    private static final java.time.format.DateTimeFormatter REOPEN_FORMAT =
        java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d", java.util.Locale.US);

    /**
     * A vote the server refuses. Carries the HTTP status and machine code the
     * controller returns, and a message the wizard shows the user verbatim.
     */
    public static class VoteRejectedException extends RuntimeException {
        private final org.springframework.http.HttpStatus status;
        private final String code;

        public VoteRejectedException(org.springframework.http.HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public org.springframework.http.HttpStatus getStatus() { return status; }
        public String getCode() { return code; }
    }

    /**
     * The calendar period a vote belongs to — identical to the window the award
     * cron closes (AwardService.getIntervalStartDate): Daily = the day,
     * Weekly = Monday–Sunday, Monthly = the calendar month, Quarterly =
     * Jan–Mar / Apr–Jun / Jul–Sep / Oct–Dec, Midterm = Jan–Jun / Jul–Dec,
     * Annual = the calendar year.
     *
     * One vote per race per period: a weekly vote cast on Sunday is spent until
     * that week's award is decided — and Monday is a new week, so the voter
     * can vote again the next day.
     */
    static LocalDate[] votingPeriod(String intervalName, LocalDate day) {
        String name = intervalName == null ? "" : intervalName;
        switch (name) {
            case "Weekly": {
                LocalDate start = day.with(DayOfWeek.MONDAY);
                return new LocalDate[] { start, start.plusDays(6) };
            }
            case "Monthly": {
                LocalDate start = day.withDayOfMonth(1);
                return new LocalDate[] { start, start.plusMonths(1).minusDays(1) };
            }
            case "Quarterly": {
                int q = (day.getMonthValue() - 1) / 3;
                LocalDate start = day.withMonth(q * 3 + 1).withDayOfMonth(1);
                return new LocalDate[] { start, start.plusMonths(3).minusDays(1) };
            }
            case "Midterm": {
                LocalDate start = day.withMonth(day.getMonthValue() >= 7 ? 7 : 1).withDayOfMonth(1);
                return new LocalDate[] { start, start.plusMonths(6).minusDays(1) };
            }
            case "Annual": {
                LocalDate start = day.withDayOfYear(1);
                return new LocalDate[] { start, start.plusYears(1).minusDays(1) };
            }
            case "Daily":
            default:
                return new LocalDate[] { day, day };
        }
    }

    private static String periodNoun(String intervalName) {
        switch (intervalName == null ? "" : intervalName) {
            case "Weekly":    return "this week";
            case "Monthly":   return "this month";
            case "Quarterly": return "this quarter";
            case "Midterm":   return "this half of the year";
            case "Annual":    return "this year";
            default:          return "today";
        }
    }

    @CacheEvict(value = {"leaderboards", "nominees", "voteCounts"}, allEntries = true)
    @Transactional
    public Vote submitVote(Vote vote) {
        // 1. GUARD CLAUSES: Validate required fields preventing NPE
        if (vote.getUser() == null) throw new IllegalArgumentException("User is required");
        if (vote.getGenre() == null) throw new IllegalArgumentException("Genre is required");
        if (vote.getJurisdiction() == null) throw new IllegalArgumentException("Jurisdiction is required");
        if (vote.getInterval() == null) throw new IllegalArgumentException("Interval is required");
        if (!"artist".equals(vote.getTargetType()) && !"song".equals(vote.getTargetType())) {
            throw new VoteRejectedException(org.springframework.http.HttpStatus.BAD_REQUEST, "TARGET_TYPE_INVALID",
                "A vote has to be for an artist or a song. Please close the wizard and try again.");
        }
        if (vote.getTargetId() == null) {
            throw new VoteRejectedException(org.springframework.http.HttpStatus.BAD_REQUEST, "TARGET_MISSING",
                "This vote is missing who it's for. Please close the wizard and try again.");
        }

        UUID userId = vote.getUser().getUserId();
        UUID targetJurisdictionId = vote.getJurisdiction().getJurisdictionId();
        LocalDate voteDate = vote.getVoteDate() != null ? vote.getVoteDate() : LocalDate.now(UNIS_ZONE);
        vote.setVoteDate(voteDate);

        // 2. Lock this voter's row for the rest of the transaction. Two submits
        //    from the same person (double-tap, two tabs) now run one after the
        //    other, so the second one always sees the first vote in step 5
        //    instead of both slipping past the duplicate check.
        entityManager.createNativeQuery("SELECT user_id FROM users WHERE user_id = :userId FOR UPDATE")
            .setParameter("userId", userId)
            .getResultList();

        // =====================================================================
        // 3. Voter eligibility — the voter must live in (or under) this race.
        // =====================================================================
        if (!canUserVoteInJurisdiction(userId, targetJurisdictionId)) {
            throw new IllegalArgumentException(
                "User is not eligible to vote in this jurisdiction. " +
                "Users can only vote in their home jurisdiction and its voting-enabled ancestors."
            );
        }

        // =====================================================================
        // 4. Nominee eligibility — the artist/song must exist, be in this
        //    genre, and be on the ballot in this jurisdiction (its home or an
        //    area above it). Previously only the wizard enforced this, so a
        //    direct API call could vote anyone into any race.
        // =====================================================================
        checkNomineeEligibility(vote);

        // =====================================================================
        // 5. One vote per race per period.
        //    Race = voter + artist/song + genre + jurisdiction + interval.
        //    Period = the interval's calendar window (see votingPeriod). The
        //    old check only looked at today's date, so a weekly or annual vote
        //    could be cast again every single day.
        // =====================================================================
        String intervalName = vote.getInterval().getName();
        LocalDate[] period = votingPeriod(intervalName, voteDate);

        Long existingCount = voteRepository.countVotesInPeriod(
                userId,
                vote.getTargetType(),
                vote.getGenre().getGenreId(),
                targetJurisdictionId,
                vote.getInterval().getIntervalId(),
                period[0],
                period[1]);

        if (existingCount != null && existingCount > 0) {
            String reopens = "Daily".equals(intervalName) || intervalName == null
                ? "tomorrow"
                : period[1].plusDays(1).format(REOPEN_FORMAT);
            String interval = intervalName == null ? "" : intervalName.toLowerCase() + " ";
            throw new VoteRejectedException(org.springframework.http.HttpStatus.CONFLICT, "ALREADY_VOTED",
                "You already cast your " + interval + vote.getGenre().getName() + " " + vote.getTargetType()
                    + " vote in " + vote.getJurisdiction().getName() + " " + periodNoun(intervalName)
                    + ". Votes can't be changed — this race opens again " + reopens + ".");
        }

        // 4. Save the vote
        Vote saved = voteRepository.save(vote);

        // 5. Update scores
        scoreUpdateService.onVote(vote.getUser().getUserId(), vote.getTargetId(), vote.getTargetType());
        awardRepository.incrementAwardEngagement(vote.getTargetType(), vote.getTargetId(), vote.getJurisdiction().getJurisdictionId(), vote.getInterval().getIntervalId());

        // 6. Update Total Votes (With NULL protection)
        if ("artist".equals(vote.getTargetType())) {
            String incrementVotes = "UPDATE users SET total_votes = COALESCE(total_votes, 0) + 1 WHERE user_id = :artistId";
            Query q = entityManager.createNativeQuery(incrementVotes);
            q.setParameter("artistId", vote.getTargetId());
            q.executeUpdate();
        } else if ("song".equals(vote.getTargetType())) {
            String incrementVotes = """
                UPDATE users SET total_votes = COALESCE(total_votes, 0) + 1 
                WHERE user_id = (SELECT artist_id FROM songs WHERE song_id = :songId)
                """;
            Query q = entityManager.createNativeQuery(incrementVotes);
            q.setParameter("songId", vote.getTargetId());
            q.executeUpdate();
        }

        if (saved.getJurisdiction() != null) { saved.getJurisdiction().getName(); }
        if (saved.getGenre() != null) { saved.getGenre().getName(); }
        if (saved.getInterval() != null) { saved.getInterval().getName(); }

        return saved;
    }

    /**
     * Server-side nominee check (mirrors what the voting wizard offers).
     *
     * A song's home is its own jurisdiction (what the nominee lists and the
     * wizard use), falling back to its artist's. Throws VoteRejectedException
     * with a message the user can act on.
     */
    private void checkNomineeEligibility(Vote vote) {
        org.springframework.http.HttpStatus forbidden = org.springframework.http.HttpStatus.FORBIDDEN;
        UUID targetId = vote.getTargetId();
        Jurisdiction race = vote.getJurisdiction();
        UUID raceGenreId = vote.getGenre().getGenreId();

        String nomineeName;
        Jurisdiction home;
        UUID nomineeGenreId;

        if ("artist".equals(vote.getTargetType())) {
            User artist = userRepository.findById(targetId).orElse(null);
            if (artist == null || artist.isDeleted() || artist.getRole() != User.Role.artist) {
                throw new VoteRejectedException(org.springframework.http.HttpStatus.NOT_FOUND, "NOMINEE_NOT_FOUND",
                    "That artist isn't on Unis anymore, so this vote can't be cast.");
            }
            nomineeName = artist.getUsername();
            home = artist.getJurisdiction();
            nomineeGenreId = artist.getGenre() != null ? artist.getGenre().getGenreId() : null;
        } else {
            Song song = songRepository.findById(targetId).orElse(null);
            if (song == null || song.getDeletedAt() != null
                    || (song.getArtist() != null && song.getArtist().isDeleted())) {
                throw new VoteRejectedException(org.springframework.http.HttpStatus.NOT_FOUND, "NOMINEE_NOT_FOUND",
                    "That song isn't on Unis anymore, so this vote can't be cast.");
            }
            nomineeName = song.getTitle();
            home = song.getJurisdiction() != null
                ? song.getJurisdiction()
                : (song.getArtist() != null ? song.getArtist().getJurisdiction() : null);
            nomineeGenreId = song.getGenre() != null ? song.getGenre().getGenreId() : null;
        }

        if (nomineeGenreId == null || !nomineeGenreId.equals(raceGenreId)) {
            throw new VoteRejectedException(forbidden, "NOMINEE_WRONG_GENRE",
                nomineeName + " isn't in the " + vote.getGenre().getName()
                    + " race, so this vote can't be counted there.");
        }

        Jurisdiction homeWithPath = home == null ? null
            : jurisdictionRepository.findById(home.getJurisdictionId()).orElse(null);
        if (homeWithPath == null) {
            throw new VoteRejectedException(forbidden, "NOMINEE_NO_HOME",
                nomineeName + " doesn't have a home area on Unis yet, so they can't be voted for.");
        }
        String nomineePath = homeWithPath.getPath();
        if (nomineePath == null || nomineePath.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(VoteService.class).error(
                "[Vote] nominee home jurisdiction {} has no path — cannot verify eligibility (target {} {})",
                homeWithPath.getJurisdictionId(), vote.getTargetType(), targetId);
            throw new VoteRejectedException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                "NOMINEE_PATH_MISSING",
                "We couldn't confirm where " + nomineeName + " can be voted on. Your vote was not counted — please try again later.");
        }

        // Same rule as voters: the race must be the nominee's home or an area
        // above it, and voting must be on there.
        if (!jurisdictionRepository.canUserVoteInJurisdiction(nomineePath, race.getJurisdictionId())) {
            throw new VoteRejectedException(forbidden, "NOMINEE_NOT_ELIGIBLE",
                nomineeName + " isn't on the ballot in " + race.getName()
                    + ". Artists and songs can only be voted for in their home area and the areas above it.");
        }
    }

    // =========================================================================
    // FIXED: Jurisdiction Eligibility Check - Now traverses FULL hierarchy
    // =========================================================================
    
    /**
     * Check if a user can vote in a specific jurisdiction.
     * 
     * Rules:
     * 1. User can vote in their HOME jurisdiction (if voting_enabled)
     * 2. User can vote in any ANCESTOR jurisdiction (if voting_enabled)
     * 3. User CANNOT vote in sibling, cousin, or unrelated jurisdictions
     * 
     * Example for Downtown Harlem user:
     * - CAN vote in: Downtown Harlem, Harlem (both voting_enabled = true)
     * - CANNOT vote in: Uptown Harlem (sibling), Brooklyn (unrelated)
     * - CANNOT vote in: Upper Manhattan (ancestor but voting_enabled = false)
     * 
     * @param userId The user attempting to vote
     * @param targetJurisdictionId The jurisdiction they want to vote in
     * @return true if eligible, false otherwise
     */
    public boolean canUserVoteInJurisdiction(UUID userId, UUID targetJurisdictionId) {
        // Get the user with their jurisdiction
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        
        Jurisdiction userJurisdiction = user.getJurisdiction();
        if (userJurisdiction == null) {
            throw new RuntimeException("User has no home jurisdiction assigned: " + userId);
        }

        // Get user's jurisdiction with path
        Jurisdiction userJurWithPath = jurisdictionRepository.findById(userJurisdiction.getJurisdictionId())
            .orElseThrow(() -> new RuntimeException("User jurisdiction not found"));
        
        String userPath = userJurWithPath.getPath();
        if (userPath == null || userPath.isEmpty()) {
            throw new RuntimeException("User jurisdiction has no path configured. Run Phase 1 SQL to populate paths.");
        }

        // Use the repository method to check eligibility
        // This checks: target is in user's path AND target is voting_enabled
        return jurisdictionRepository.canUserVoteInJurisdiction(userPath, targetJurisdictionId);
    }

    /**
     * Get all jurisdictions where a user is eligible to vote.
     * Used for populating the jurisdiction dropdown in the voting UI.
     * 
     * @param userId The user
     * @return List of voting-enabled jurisdictions in user's ancestor chain
     */
    public List<Jurisdiction> getEligibleJurisdictionsForUser(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        
        Jurisdiction userJurisdiction = user.getJurisdiction();
        if (userJurisdiction == null) {
            return new ArrayList<>();
        }

        Jurisdiction userJurWithPath = jurisdictionRepository.findById(userJurisdiction.getJurisdictionId())
            .orElseThrow(() -> new RuntimeException("User jurisdiction not found"));
        
        String userPath = userJurWithPath.getPath();
        if (userPath == null || userPath.isEmpty()) {
            return new ArrayList<>();
        }

        // Get voting-enabled ancestors
        List<Object[]> results = jurisdictionRepository.findVotingEnabledAncestors(userPath);
        
        // Convert to Jurisdiction entities
        List<UUID> jurisdictionIds = results.stream()
            .map(row -> (UUID) row[0])
            .collect(Collectors.toList());
        
        if (jurisdictionIds.isEmpty()) {
            return new ArrayList<>();
        }

        List<Jurisdiction> jurisdictions = jurisdictionRepository.findAllById(jurisdictionIds);
        
        // Sort by depth (deepest first - user's home jurisdiction first)
        jurisdictions.sort((a, b) -> {
            int depthA = a.getDepth() != null ? a.getDepth() : 0;
            int depthB = b.getDepth() != null ? b.getDepth() : 0;
            return Integer.compare(depthB, depthA);
        });
        
        return jurisdictions;
    }

    @Cacheable(value = "voteCounts", key = "'total-' + #targetType + '-' + #targetId")
    public Long getTotalVotesForTarget(String targetType, UUID targetId) {
        if (!"song".equals(targetType) && !"artist".equals(targetType)) {
            throw new IllegalArgumentException("Invalid targetType: " + targetType + " (song or artist only)");
        }
        return voteRepository.countByTarget(targetType, targetId);
    }

    // NOT CACHED - User-specific query, low reuse across users
    public Long getVotesCastByUser(UUID userId) {
        return voteRepository.countByUserId(userId);
    }

    // NOT CACHED - Admin/filter query, infrequent access
    public List<Vote> getVotesByJurisdictionGenreInterval(UUID jurisdictionId, UUID genreId, UUID intervalId) {
        List<Vote> votes = voteRepository.findByJurisdictionGenreInterval(jurisdictionId, genreId, intervalId);
        return votes.stream().filter(v -> "song".equals(v.getTargetType()) || "artist".equals(v.getTargetType())).collect(Collectors.toList());
    }

    // NOT CACHED - Scheduled cron job (runs once daily at midnight)
    // NOTE: Award computation moved to AwardService - this is kept for backwards compatibility
    @Scheduled(cron = "0 0 0 * * ?")
    public void computeDailyAwards() {
        Optional<VotingInterval> dailyInterval = votingIntervalRepository.findByName("Daily");
        if (dailyInterval.isEmpty()) return;

        UUID dailyId = dailyInterval.get().getIntervalId();
        // FIXED: Only compute for voting-enabled jurisdictions
        List<UUID> jurisdictions = jurisdictionRepository.findVotingEnabledJurisdictionIds();
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

    // CACHED: Get nominees ranked by vote count (1 min TTL via "nominees" cache)
    // Expensive query with recursive CTE, aggregations, and date filtering
    // Cache key includes all parameters since filters change results
    @Cacheable(value = "nominees", key = "'nominees-' + #targetType + '-' + #genreId + '-' + #jurisdictionId + '-' + #intervalId + '-' + #limit")
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
            
            for (Song song : songs) {
            Long totalPlays = songPlayRepository.countTotalPlaysBySongId(song.getSongId());
            song.setPlayCount(totalPlays != null ? totalPlays : 0L);
        }

            return songs;
        }
    }

    // NOT CACHED - Private helper method
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

    // NOT CACHED - Private helper method
    // Helper: Get start date for interval
    private LocalDate getIntervalStartDate(UUID intervalId) {
        VotingInterval interval = votingIntervalRepository.findById(intervalId)
            .orElseThrow(() -> new RuntimeException("Interval not found"));
        
        LocalDate today = LocalDate.now();
        
        switch (interval.getName()) {
            case "Daily":
                return today;  // PROD: reverted from today.minusDays(1) (was loosened for testing)
            case "Weekly":
                return today.with(DayOfWeek.MONDAY);
            case "Monthly":
                return today.withDayOfMonth(1);
            case "Quarterly":
                int currentQuarter = (today.getMonthValue() - 1) / 3;
                return today.withMonth(currentQuarter * 3 + 1).withDayOfMonth(1);
            case "Midterm":
                int month = today.getMonthValue();
                if (month >= 7) {
                    return today.withMonth(7).withDayOfMonth(1);
                } else {
                    return today.withMonth(1).withDayOfMonth(1);
                }
            case "Annual":
                return today.withDayOfYear(1);
            default:
                return today;
        }
    }

    // CACHED: Live leaderboard rankings (1 min TTL via "leaderboards" cache)
    // MOST EXPENSIVE QUERY in the entire application:
    // - Recursive CTE for jurisdiction hierarchy
    // - Multiple LEFT JOINs (votes + song_plays)
    // - COUNT aggregations across millions of rows
    // - Date range filtering
    // - Fallback query doubles the work if <5 results
    // Cache key includes all filter parameters since each combination produces different results
    @Cacheable(value = "leaderboards", key = "'live-' + #targetType + '-' + #genreId + '-' + #jurisdictionId + '-' + #intervalId + '-' + #limit")
    @SuppressWarnings("unchecked")
    public List<LeaderboardDto> getLeaderboard(String targetType, UUID genreId, UUID jurisdictionId, UUID intervalId, int limit) {
        LocalDate startDate = getIntervalStartDate(intervalId);
        LocalDate endDate = LocalDate.now();
        // Exclusive upper bound for song_plays.played_at (a timestamp column).
        // The old queries wrapped it in DATE(sp.played_at), which defeats any
        // index on played_at; a half-open range from startDate inclusive to
        // endDate+1 exclusive is sargable and covers the same days.
        LocalDate endExclusive = endDate.plusDays(1);
        List<UUID> jurisdictionIds = getJurisdictionHierarchy(jurisdictionId);

        // SCORING NOTE: score = votes-in-interval + plays-in-window. The old
        // implementation computed this with two LEFT JOINs on the same row set
        // (votes AND song_plays), which cross-multiplies: an artist with
        // 3 votes and 5 plays produced 15 joined rows, so COUNT(vote_id) = 15
        // and COUNT(play_id) = 15 — score 30 instead of 8, skewing the whole
        // ranking toward anyone with both. Votes and plays are now
        // pre-aggregated in their own CTEs and joined one-row-per-target,
        // which both fixes the inflation and removes the fanout cost.
        if ("artist".equalsIgnoreCase(targetType)) {
            String query = """
            WITH RECURSIVE jurisdiction_hierarchy AS (
                SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
                UNION ALL
                SELECT j.jurisdiction_id FROM jurisdictions j INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
            ),
            vote_counts AS (
                SELECT v.target_id, COUNT(*) AS vote_count
                FROM votes v
                WHERE v.target_type = 'artist' AND v.genre_id = :genreId
                  AND v.jurisdiction_id IN (:jurisdictionIds) AND v.interval_id = :intervalId
                  AND v.vote_date BETWEEN :startDate AND :endDate
                GROUP BY v.target_id
            ),
            play_counts AS (
                SELECT s.artist_id, COUNT(*) AS play_count
                FROM song_plays sp
                JOIN songs s ON s.song_id = sp.song_id
                WHERE sp.played_at >= :startDate AND sp.played_at < :endExclusive
                GROUP BY s.artist_id
            )
            SELECT u.user_id, u.username,
                   COALESCE(vc.vote_count, 0) + COALESCE(pc.play_count, 0) AS score,
                   u.photo_url
            FROM users u
            JOIN jurisdiction_hierarchy jh ON u.jurisdiction_id = jh.jurisdiction_id
            LEFT JOIN vote_counts vc ON vc.target_id = u.user_id
            LEFT JOIN play_counts pc ON pc.artist_id = u.user_id
            WHERE u.role = 'artist' AND u.genre_id = :genreId
            ORDER BY score DESC, COALESCE(vc.vote_count, 0) DESC
            LIMIT :limit
            """;
            Query q = entityManager.createNativeQuery(query);
            q.setParameter("jurisdictionId", jurisdictionId);
            q.setParameter("genreId", genreId);
            q.setParameter("jurisdictionIds", jurisdictionIds);
            q.setParameter("intervalId", intervalId);
            q.setParameter("startDate", startDate);
            q.setParameter("endDate", endDate);
            q.setParameter("endExclusive", endExclusive);
            q.setParameter("limit", limit);
            List<Object[]> results = q.getResultList();

            // Fallback if <5: Top by plays only. Excludes rows already
            // returned by the main query — the old version re-selected the
            // same artists, so anyone appearing in both showed up twice.
            if (results.size() < 5 && !results.isEmpty()) {
                List<UUID> excludeIds = new ArrayList<>();
                for (Object[] row : results) excludeIds.add((UUID) row[0]);

                String fallbackQuery = """
                WITH RECURSIVE jurisdiction_hierarchy AS (
                    SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
                    UNION ALL
                    SELECT j.jurisdiction_id FROM jurisdictions j INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
                ),
                play_counts AS (
                    SELECT s.artist_id, COUNT(*) AS play_count
                    FROM song_plays sp
                    JOIN songs s ON s.song_id = sp.song_id
                    WHERE sp.played_at >= :startDate AND sp.played_at < :endExclusive
                    GROUP BY s.artist_id
                )
                SELECT u.user_id, u.username, COALESCE(pc.play_count, 0) AS score, u.photo_url
                FROM users u
                JOIN jurisdiction_hierarchy jh ON u.jurisdiction_id = jh.jurisdiction_id
                LEFT JOIN play_counts pc ON pc.artist_id = u.user_id
                WHERE u.role = 'artist' AND u.genre_id = :genreId
                  AND u.user_id NOT IN (:excludeIds)
                ORDER BY score DESC
                LIMIT :fallbackLimit
                """;
                Query fq = entityManager.createNativeQuery(fallbackQuery);
                fq.setParameter("jurisdictionId", jurisdictionId);
                fq.setParameter("genreId", genreId);
                fq.setParameter("startDate", startDate);
                fq.setParameter("endExclusive", endExclusive);
                fq.setParameter("excludeIds", excludeIds);
                fq.setParameter("fallbackLimit", 5 - results.size());
                List<Object[]> fallback = fq.getResultList();
                results.addAll(fallback);
            }

            List<LeaderboardDto> leaderboard = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                Object[] row = results.get(i);
                leaderboard.add(LeaderboardDto.builder()
                    .rank(i + 1)
                    .targetId((UUID) row[0])  // user_id
                    .name(row[1].toString())
                    .votes(((Number) row[2]).longValue())
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
            ),
            vote_counts AS (
                SELECT v.target_id, COUNT(*) AS vote_count
                FROM votes v
                WHERE v.target_type = 'song' AND v.genre_id = :genreId
                  AND v.jurisdiction_id IN (:jurisdictionIds) AND v.interval_id = :intervalId
                  AND v.vote_date BETWEEN :startDate AND :endDate
                GROUP BY v.target_id
            ),
            play_counts AS (
                SELECT sp.song_id, COUNT(*) AS play_count
                FROM song_plays sp
                WHERE sp.played_at >= :startDate AND sp.played_at < :endExclusive
                GROUP BY sp.song_id
            )
            SELECT s.song_id, s.title,
                   COALESCE(vc.vote_count, 0) + COALESCE(pc.play_count, 0) AS score,
                   s.artwork_url, a.username AS artist, s.file_url
            FROM songs s
            INNER JOIN users a ON s.artist_id = a.user_id
            JOIN jurisdiction_hierarchy jh ON a.jurisdiction_id = jh.jurisdiction_id
            LEFT JOIN vote_counts vc ON vc.target_id = s.song_id
            LEFT JOIN play_counts pc ON pc.song_id = s.song_id
            WHERE s.genre_id = :genreId
            ORDER BY score DESC, COALESCE(vc.vote_count, 0) DESC
            LIMIT :limit
            """;
            Query q = entityManager.createNativeQuery(query);
            q.setParameter("jurisdictionId", jurisdictionId);
            q.setParameter("genreId", genreId);
            q.setParameter("jurisdictionIds", jurisdictionIds);
            q.setParameter("intervalId", intervalId);
            q.setParameter("startDate", startDate);
            q.setParameter("endDate", endDate);
            q.setParameter("endExclusive", endExclusive);
            q.setParameter("limit", limit);
            List<Object[]> results = q.getResultList();

            // Fallback if <5: Top by plays only, excluding already-returned songs
            if (results.size() < 5 && !results.isEmpty()) {
                List<UUID> excludeIds = new ArrayList<>();
                for (Object[] row : results) excludeIds.add((UUID) row[0]);

                String fallbackQuery = """
                WITH RECURSIVE jurisdiction_hierarchy AS (
                    SELECT jurisdiction_id FROM jurisdictions WHERE jurisdiction_id = :jurisdictionId
                    UNION ALL
                    SELECT j.jurisdiction_id FROM jurisdictions j INNER JOIN jurisdiction_hierarchy jh ON j.parent_jurisdiction_id = jh.jurisdiction_id
                ),
                play_counts AS (
                    SELECT sp.song_id, COUNT(*) AS play_count
                    FROM song_plays sp
                    WHERE sp.played_at >= :startDate AND sp.played_at < :endExclusive
                    GROUP BY sp.song_id
                )
                SELECT s.song_id, s.title, COALESCE(pc.play_count, 0) AS score,
                       s.artwork_url, a.username AS artist, s.file_url
                FROM songs s
                INNER JOIN users a ON s.artist_id = a.user_id
                JOIN jurisdiction_hierarchy jh ON a.jurisdiction_id = jh.jurisdiction_id
                LEFT JOIN play_counts pc ON pc.song_id = s.song_id
                WHERE s.genre_id = :genreId
                  AND s.song_id NOT IN (:excludeIds)
                ORDER BY score DESC
                LIMIT :fallbackLimit
                """;
                Query fq = entityManager.createNativeQuery(fallbackQuery);
                fq.setParameter("jurisdictionId", jurisdictionId);
                fq.setParameter("genreId", genreId);
                fq.setParameter("startDate", startDate);
                fq.setParameter("endExclusive", endExclusive);
                fq.setParameter("excludeIds", excludeIds);
                fq.setParameter("fallbackLimit", 5 - results.size());
                List<Object[]> fallback = fq.getResultList();
                results.addAll(fallback);
            }

            List<LeaderboardDto> leaderboard = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                Object[] row = results.get(i);
                leaderboard.add(LeaderboardDto.builder()
                    .rank(i + 1)
                    .targetId((UUID) row[0])  // song_id
                    .name(row[1].toString())
                    .votes(((Number) row[2]).longValue())
                    .artwork(row[3] != null ? row[3].toString() : null)
                    .artist(row[4] != null ? row[4].toString() : null)
                    .fileUrl(row[5] != null ? row[5].toString() : null)
                    .build());
            }
            return leaderboard;
        }
    }

}