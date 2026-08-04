package com.unis.controller;

import com.unis.dto.LeaderboardDto;
import com.unis.dto.VoteHistoryDto;
import com.unis.dto.VoteRequest;
import com.unis.entity.Vote;
import com.unis.entity.VotingInterval;
import com.unis.entity.Song;
import com.unis.entity.Genre;
import com.unis.entity.Jurisdiction;
import com.unis.entity.User;
import com.unis.util.SecurityUtils;
import com.unis.repository.UserRepository;
import com.unis.repository.SongRepository;
import com.unis.repository.VoteRepository;
import com.unis.repository.GenreRepository;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.VotingIntervalRepository;
import com.unis.service.AwardService;
import com.unis.service.VoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vote")
public class VoteController {
    @Autowired
    private VoteService voteService;

    @Autowired
    private AwardService awardService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private JurisdictionRepository jurisdictionRepository;

    @Autowired
    private VotingIntervalRepository votingIntervalRepository;

    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private SongRepository songRepository;

    // POST /api/v1/vote/submit
    // ★ ironclad: every failure path returns structured JSON {code, message}
    //   with the correct status — the frontend surfaces `message` verbatim, so
    //   a vote can never fail without a clear explanation.
    private static final java.time.ZoneId UNIS_ZONE = java.time.ZoneId.of("America/New_York");

    private ResponseEntity<Map<String, Object>> voteError(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    @PostMapping("/submit")
    public ResponseEntity<?> submitVote(@RequestBody VoteRequest req) {
        try {
            // C6 FIX: Get userId from JWT, not from request body
            UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();

            // Fetch full User object from authenticated userId (not req.getUserId())
            User user = userRepository.findById(authenticatedUserId).orElse(null);
            if (user == null) { // ★ ironclad: 401-adjacent state, not a 500
                return voteError(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND",
                    "We could not identify your account. Please log out and back in.");
            }

            if (!Boolean.TRUE.equals(user.getPhoneVerified())) {
                return voteError(HttpStatus.FORBIDDEN, "PHONE_UNVERIFIED",
                    "Verify your phone number to vote.");
            }

            // ★ ironclad: missing/unknown IDs are 400s with named messages —
            //   previously unknown UUIDs threw RuntimeException → 500 → the
            //   frontend showed "Connection Failed" with no explanation.
            if (req.getGenreId() == null) {
                return voteError(HttpStatus.BAD_REQUEST, "GENRE_MISSING",
                    "This vote is missing its genre. Please close the wizard and try again.");
            }
            Genre genre = genreRepository.findById(req.getGenreId()).orElse(null);
            if (genre == null) {
                return voteError(HttpStatus.BAD_REQUEST, "GENRE_NOT_FOUND",
                    "That genre no longer exists on Unis. Please refresh and try again.");
            }

            if (req.getJurisdictionId() == null) {
                return voteError(HttpStatus.BAD_REQUEST, "JURISDICTION_MISSING",
                    "This vote is missing its jurisdiction. Please close the wizard and try again.");
            }
            Jurisdiction jurisdiction = jurisdictionRepository.findById(req.getJurisdictionId()).orElse(null);
            if (jurisdiction == null) {
                return voteError(HttpStatus.BAD_REQUEST, "JURISDICTION_NOT_FOUND",
                    "That jurisdiction no longer exists on Unis. Please refresh and try again.");
            }

            if (req.getIntervalId() == null) {
                return voteError(HttpStatus.BAD_REQUEST, "INTERVAL_MISSING",
                    "This vote is missing its interval. Please close the wizard and try again.");
            }
            VotingInterval interval = votingIntervalRepository.findById(req.getIntervalId()).orElse(null);
            if (interval == null) {
                return voteError(HttpStatus.BAD_REQUEST, "INTERVAL_NOT_FOUND",
                    "That voting interval no longer exists on Unis. Please refresh and try again.");
            }

            if (jurisdiction.getVotingEnabled() == null || !jurisdiction.getVotingEnabled()) {
                return voteError(HttpStatus.FORBIDDEN, "VOTING_DISABLED",
                    "Voting is not enabled in " + jurisdiction.getName() + " yet.");
            }

            // C6 FIX: Use authenticatedUserId instead of req.getUserId() for eligibility check
            if (!voteService.canUserVoteInJurisdiction(authenticatedUserId, req.getJurisdictionId())) {
                return voteError(HttpStatus.FORBIDDEN, "NOT_ELIGIBLE",
                    "You are not eligible to vote in " + jurisdiction.getName()
                        + ". You can only vote in your home jurisdiction and its parent jurisdictions.");
            }

            // ★ ironclad: the SERVER stamps the vote date in the platform
            //   timezone. The client previously sent a UTC-derived date, which
            //   rolled to "tomorrow" after ~8pm New York time — causing phantom
            //   duplicate rejections — and let a malicious client fabricate any
            //   date to bypass the one-vote-per-day rule entirely.
            java.time.LocalDate voteDate = java.time.LocalDate.now(UNIS_ZONE);

            // Build Vote entity
            Vote vote = Vote.builder()
                .user(user)
                .targetType(req.getTargetType())
                .targetId(req.getTargetId())
                .genre(genre)
                .jurisdiction(jurisdiction)
                .interval(interval)
                .voteDate(voteDate)
                .build();

            Vote saved = voteService.submitVote(vote);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Vote cast successfully");
            response.put("voteId", saved.getVoteId());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            // Service-level validation (missing fields, eligibility re-check)
            if (e.getMessage() != null && e.getMessage().contains("not eligible")) { // ★ ironclad
                return voteError(HttpStatus.FORBIDDEN, "NOT_ELIGIBLE", e.getMessage());
            }
            return voteError(HttpStatus.BAD_REQUEST, "VOTE_INVALID", e.getMessage());
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("already cast")) {
                return voteError(HttpStatus.CONFLICT, "ALREADY_VOTED", e.getMessage());
            }
            if (e.getMessage() != null && e.getMessage().contains("not eligible")) {
                return voteError(HttpStatus.FORBIDDEN, "NOT_ELIGIBLE", e.getMessage());
            }
            // ★ ironclad: log the full stack server-side; give the user a real
            //   sentence instead of a bare 500.
            org.slf4j.LoggerFactory.getLogger(VoteController.class)
                .error("Vote submission failed unexpectedly for request {}", req, e);
            return voteError(HttpStatus.INTERNAL_SERVER_ERROR, "VOTE_FAILED",
                "Something went wrong saving your vote — it was NOT counted. Please try again in a moment.");
        }
    }

    // C6 FIX: Get eligible jurisdictions using authenticated user, not query param
    @GetMapping("/eligible-jurisdictions")
    public ResponseEntity<List<Jurisdiction>> getEligibleJurisdictions(@RequestParam(required = false) UUID userId) {
        try {
            // Use JWT userId if available, fall back to query param for backward compatibility
            UUID resolvedUserId;
            try {
                resolvedUserId = SecurityUtils.getAuthenticatedUserId();
            } catch (Exception e) {
                resolvedUserId = userId;
            }
            List<Jurisdiction> jurisdictions = voteService.getEligibleJurisdictionsForUser(resolvedUserId);
            return ResponseEntity.ok(jurisdictions);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/results")
    public ResponseEntity<List<Vote>> getVoteResults(
            @RequestParam String type,
            @RequestParam(required = false) UUID jurisdictionId,
            @RequestParam(required = false) UUID genreId,
            @RequestParam(required = false) UUID intervalId) {
        List<Vote> results = voteService.getVotesByJurisdictionGenreInterval(jurisdictionId, genreId, intervalId);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/total/{targetType}/{targetId}")
    public ResponseEntity<Long> getTotalVotes(@PathVariable String targetType, @PathVariable UUID targetId) {
        Long total = voteService.getTotalVotesForTarget(targetType, targetId);
        return ResponseEntity.ok(total);
    }

    @GetMapping("/votes/user/{userId}")
    public ResponseEntity<Long> getVotesCastByUser(@PathVariable UUID userId) {
        Long count = voteService.getVotesCastByUser(userId);
        return ResponseEntity.ok(count);
    }

    @GetMapping("/nominees")
    public ResponseEntity<?> getNominees(
            @RequestParam String targetType,
            @RequestParam UUID genreId,
            @RequestParam UUID jurisdictionId,
            @RequestParam UUID intervalId,
            @RequestParam(defaultValue = "20") int limit) {
        
        List<?> nominees = voteService.getNominees(targetType, genreId, jurisdictionId, intervalId, limit);
        return ResponseEntity.ok(nominees);
    }

    @GetMapping("/check-eligibility")
    public ResponseEntity<Boolean> checkEligibility(
            @RequestParam UUID userId,
            @RequestParam UUID jurisdictionId) {
        
        boolean canVote = voteService.canUserVoteInJurisdiction(userId, jurisdictionId);
        return ResponseEntity.ok(canVote);
    }

    @GetMapping("/leaderboards")
    public ResponseEntity<List<LeaderboardDto>> getLeaderboards(
        @RequestParam UUID jurisdictionId,
        @RequestParam UUID genreId,
        @RequestParam String targetType,
        @RequestParam UUID intervalId,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(required = false) boolean playsOnly) {
        List<LeaderboardDto> leaderboard = voteService.getLeaderboard(targetType, genreId, jurisdictionId, intervalId, limit);
        return ResponseEntity.ok(leaderboard);
    }

    @PostMapping("/awards/compute")
    public ResponseEntity<String> computeAwards(
        @RequestParam UUID intervalId,
        @RequestParam UUID jurisdictionId,
        @RequestParam UUID genreId,
        @RequestParam(required = false) LocalDate date) {
        LocalDate cronDate = date != null ? date : LocalDate.now();
        awardService.computeForInterval(intervalId, jurisdictionId, genreId, cronDate);
        return ResponseEntity.ok("Computed for " + cronDate);
    }

    // GET /api/v1/vote/history — already uses Authentication correctly, no C6 change needed
    @GetMapping("/history")
    public ResponseEntity<List<VoteHistoryDto>> getVoteHistory(
            Authentication auth,
            @RequestParam(defaultValue = "50") int limit) {

        String email = auth.getName();
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));

        List<Vote> votes = voteRepository.findByUserUserIdOrderByVoteDateDesc(user.getUserId());

        List<VoteHistoryDto> history = new ArrayList<>();
        int count = 0;

        for (Vote vote : votes) {
            if (count >= limit) break;

            String nomineeName = "Unknown";
            String nomineeImage = null;

            if ("song".equalsIgnoreCase(vote.getTargetType())) {
                Optional<Song> songOpt = songRepository.findById(vote.getTargetId());
                if (songOpt.isPresent()) {
                    Song song = songOpt.get();
                    nomineeName = song.getTitle();
                    nomineeImage = song.getArtworkUrl();
                }
            } else if ("artist".equalsIgnoreCase(vote.getTargetType())) {
                Optional<User> artistOpt = userRepository.findById(vote.getTargetId());
                if (artistOpt.isPresent()) {
                    User artist = artistOpt.get();
                    nomineeName = artist.getUsername();
                    nomineeImage = artist.getPhotoUrl();
                }
            }

            String intervalName = vote.getInterval() != null
                ? vote.getInterval().getName()
                : "day";

            VoteHistoryDto dto = VoteHistoryDto.builder()
                .voteId(vote.getVoteId())
                .targetType(vote.getTargetType())
                .targetId(vote.getTargetId())
                .nomineeName(nomineeName)
                .nomineeImage(nomineeImage)
                .voteDate(vote.getVoteDate())
                .interval(intervalName)
                .build();

            history.add(dto);
            count++;
        }

        return ResponseEntity.ok(history);
    }
}