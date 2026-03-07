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
    @PostMapping("/submit")
    public ResponseEntity<?> submitVote(@RequestBody VoteRequest req) {
        try {
            // C6 FIX: Get userId from JWT, not from request body
            UUID authenticatedUserId = SecurityUtils.getAuthenticatedUserId();

            // Fetch full User object from authenticated userId (not req.getUserId())
            User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(() -> new RuntimeException("User not found: " + authenticatedUserId));

            // Fetch Genre, Jurisdiction, Interval (required for voting)
            if (req.getGenreId() == null) {
                return ResponseEntity.badRequest().body("Genre is required for voting");
            }
            Genre genre = genreRepository.findById(req.getGenreId())
                .orElseThrow(() -> new RuntimeException("Genre not found: " + req.getGenreId()));

            if (req.getJurisdictionId() == null) {
                return ResponseEntity.badRequest().body("Jurisdiction is required for voting");
            }
            Jurisdiction jurisdiction = jurisdictionRepository.findById(req.getJurisdictionId())
                .orElseThrow(() -> new RuntimeException("Jurisdiction not found: " + req.getJurisdictionId()));

            if (req.getIntervalId() == null) {
                return ResponseEntity.badRequest().body("Interval is required for voting");
            }
            VotingInterval interval = votingIntervalRepository.findById(req.getIntervalId())
                .orElseThrow(() -> new RuntimeException("Interval not found: " + req.getIntervalId()));

            if (jurisdiction.getVotingEnabled() == null || !jurisdiction.getVotingEnabled()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Voting is not enabled for this jurisdiction: " + jurisdiction.getName());
            }

            // C6 FIX: Use authenticatedUserId instead of req.getUserId() for eligibility check
            if (!voteService.canUserVoteInJurisdiction(authenticatedUserId, req.getJurisdictionId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("You are not eligible to vote in " + jurisdiction.getName() + 
                          ". You can only vote in your home jurisdiction and its parent jurisdictions.");
            }

            // Build Vote entity
            Vote vote = Vote.builder()
                .user(user)
                .targetType(req.getTargetType())
                .targetId(req.getTargetId())
                .genre(genre)
                .jurisdiction(jurisdiction)
                .interval(interval)
                .voteDate(req.getVoteDate())
                .build();

            Vote saved = voteService.submitVote(vote);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Vote cast successfully");
            response.put("voteId", saved.getVoteId());
            
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("already cast")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
            }
            if (e.getMessage() != null && e.getMessage().contains("not eligible")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Failed to submit vote: " + e.getMessage());
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
        System.out.println("Leaderboards hit: jur=" + jurisdictionId + ", genre=" + genreId + ", type=" + targetType + ", interval=" + intervalId + ", limit=" + limit + ", playsOnly=" + playsOnly);
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