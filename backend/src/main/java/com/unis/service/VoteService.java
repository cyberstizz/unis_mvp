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
import com.unis.dto.RaceStandingsDto;
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

    @Autowired
    private RaceClockService raceClock;

    @Autowired
    private RaceStandingsService raceStandingsService;

    // =========================================================================
    // VOTE RULES
    //
    // A vote counts on the VOTER's own calendar day (RaceClockService): the
    // controller stamps vote_date on the voter's home clock, so an 11:59 PM
    // vote in New York is that day's and a 12:01 AM vote is the next day's.
    // =========================================================================

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
     * The calendar period a vote belongs to — the same window the award job
     * closes (RaceClockService.periodContaining): Daily = the day,
     * Weekly = Monday–Sunday, Monthly = the calendar month, Quarterly =
     * Jan–Mar / Apr–Jun / Jul–Sep / Oct–Dec, Midterm = Jan–Jun / Jul–Dec,
     * Annual = the calendar year.
     *
     * One vote per race per period: a weekly vote cast on Sunday is spent until
     * that week's award is decided — and Monday is a new week, so the voter
     * can vote again the next day.
     */
    static LocalDate[] votingPeriod(String intervalName, LocalDate day) {
        return RaceClockService.periodContaining(intervalName, day);
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
        LocalDate voteDate = vote.getVoteDate() != null ? vote.getVoteDate() : raceClock.todayForUser(userId);
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

    // The old computeDailyAwards cron that lived here is gone. It fired at
    // midnight on the SERVER's clock (8 PM New York time on UTC), wrote song
    // award rows from all-time vote counts with no genre filter, and could
    // block the real award for the slot. Awards now come only from
    // RaceCloseScheduler.

    /**
     * Nominees for the voting page, in standings order for the period this
     * viewer's vote would count toward (their own day), using the same formula
     * that decides the award. Before anyone scores in that period, the previous
     * period's order is used (the midnight rule — RaceStandingsService).
     *
     * Returns User entities for artist races and Song entities for song races,
     * the shape the voting page already reads. Anything removed since the
     * previous period closed is left off — it can't be voted for.
     */
    public List<?> getNominees(String targetType, UUID genreId, UUID jurisdictionId, UUID intervalId, int limit) {
        VotingInterval interval = votingIntervalRepository.findById(intervalId)
            .orElseThrow(() -> new RuntimeException("Interval not found"));
        String type = RaceStandingsService.normalizeType(targetType);
        LocalDate viewDate = raceClock.viewerDateForRace(jurisdictionId);

        RaceStandingsService.Resolved resolved = raceStandingsService.resolve(
            type, jurisdictionId, genreId, interval, viewDate, Math.min(Math.max(limit, 1), 100), 0);

        List<UUID> ids = resolved.candidates().stream()
            .map(RankingService.Candidate::targetId)
            .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return new ArrayList<>();
        }
        Map<UUID, Integer> order = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) order.put(ids.get(i), i);

        if ("artist".equals(type)) {
            List<User> artists = userRepository.findAllById(ids).stream()
                .filter(u -> !u.isDeleted())
                .sorted(Comparator.comparingInt(u -> order.getOrDefault(u.getUserId(), Integer.MAX_VALUE)))
                .collect(Collectors.toList());
            return artists;
        }

        List<Song> songs = songRepository.findAllById(ids).stream()
            .filter(sg -> sg.getDeletedAt() == null)
            .filter(sg -> sg.getArtist() == null || !sg.getArtist().isDeleted())
            .sorted(Comparator.comparingInt(sg -> order.getOrDefault(sg.getSongId(), Integer.MAX_VALUE)))
            .collect(Collectors.toList());
        for (Song song : songs) {
            Long totalPlays = songPlayRepository.countTotalPlaysBySongId(song.getSongId());
            song.setPlayCount(totalPlays != null ? totalPlays : 0L);
        }
        return songs;
    }

    /**
     * The leaderboards page: the race's live board (RaceStandingsService), in
     * the row shape the page already reads. `votes` carries total points —
     * the number the race is decided on — with the breakdown alongside.
     */
    public List<LeaderboardDto> getLeaderboard(String targetType, UUID genreId, UUID jurisdictionId, UUID intervalId, int limit) {
        RaceStandingsDto board = raceStandingsService.standings(
            targetType, jurisdictionId, genreId, intervalId, limit, 0,
            raceClock.viewerDateForRace(jurisdictionId));

        boolean songs = "song".equals(board.getType());
        List<LeaderboardDto> rows = new ArrayList<>();
        for (RaceStandingsDto.Entry e : board.getEntries()) {
            rows.add(LeaderboardDto.builder()
                .rank(e.getRank())
                .targetId(e.getTargetId())
                .name(e.getName() != null ? e.getName() : "Unknown")
                .votes(e.getTotalPoints())
                .artwork(e.getArtworkUrl())
                .artist(songs ? e.getArtistName() : null)
                .fileUrl(songs ? e.getFileUrl() : null)
                .artistId(e.getArtistId())
                .totalPoints(e.getTotalPoints())
                .voteCount(e.getVotes())
                .plays(e.getPlays())
                .likes(e.getLikes())
                .build());
        }
        return rows;
    }

}
