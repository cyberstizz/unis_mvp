package com.unis.controller;

import com.unis.dto.LeaderboardDto;
import com.unis.dto.VoteRequest;
import com.unis.entity.Vote;
import com.unis.entity.VotingInterval;
import com.unis.entity.Genre;
import com.unis.entity.Jurisdiction;
import com.unis.entity.User;
import com.unis.repository.UserRepository;
import com.unis.repository.GenreRepository;
import com.unis.repository.JurisdictionRepository;
import com.unis.repository.VotingIntervalRepository;
import com.unis.service.VoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vote")
public class VoteController {
    @Autowired
    private VoteService voteService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private JurisdictionRepository jurisdictionRepository;

    @Autowired
    private VotingIntervalRepository votingIntervalRepository;

    // POST /api/v1/vote/submit (submit vote, page 2)
    @PostMapping("/submit")
    public ResponseEntity<Vote> submitVote(@RequestBody VoteRequest req) {
        // Fetch full User object from userId
        User user = userRepository.findById(req.getUserId())
            .orElseThrow(() -> new RuntimeException("User not found: " + req.getUserId()));

        // Fetch Genre, Jurisdiction, Interval (optional—null OK if not provided)
        Genre genre = null;
        if (req.getGenreId() != null) {
            genre = genreRepository.findById(req.getGenreId())
                .orElseThrow(() -> new RuntimeException("Genre not found: " + req.getGenreId()));
        }

        Jurisdiction jurisdiction = null;
        if (req.getJurisdictionId() != null) {
            jurisdiction = jurisdictionRepository.findById(req.getJurisdictionId())
                .orElseThrow(() -> new RuntimeException("Jurisdiction not found: " + req.getJurisdictionId()));
        }

        VotingInterval interval = null;
        if (req.getIntervalId() != null) {
            interval = votingIntervalRepository.findById(req.getIntervalId())
                .orElseThrow(() -> new RuntimeException("Interval not found: " + req.getIntervalId()));
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
        return ResponseEntity.ok(saved);
    }

    // GET /api/v1/vote/results?type={type}&jurisdictionId={id}&genreId={id}&intervalId={id} (vote page results, page 2)
    @GetMapping("/results")
    public ResponseEntity<List<Vote>> getVoteResults(
            @RequestParam String type,
            @RequestParam(required = false) UUID jurisdictionId,
            @RequestParam(required = false) UUID genreId,
            @RequestParam(required = false) UUID intervalId) {
        List<Vote> results = voteService.getVotesByJurisdictionGenreInterval(jurisdictionId, genreId, intervalId);
        return ResponseEntity.ok(results);
    }

    // GET /api/v1/vote/total/{targetType}/{targetId} (total votes for card, page 2)
    @GetMapping("/total/{targetType}/{targetId}")
    public ResponseEntity<Long> getTotalVotes(@PathVariable String targetType, @PathVariable UUID targetId) {
        Long total = voteService.getTotalVotesForTarget(targetType, targetId);
        return ResponseEntity.ok(total);
    }

    // GET /api/v1/vote/votes/user/{userId} (votes cast by user, for score)
    @GetMapping("/votes/user/{userId}")
    public ResponseEntity<Long> getVotesCastByUser(@PathVariable UUID userId) {
        Long count = voteService.getVotesCastByUser(userId);
        return ResponseEntity.ok(count);
    }


    // GET /api/v1/vote/nominees - Get top nominees by vote count
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

    // GET /api/v1/vote/check-eligibility
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
        @RequestParam(required = false) boolean playsOnly) {  // For fallback
        System.out.println("Leaderboards hit: jur=" + jurisdictionId + ", genre=" + genreId + ", type=" + targetType + ", interval=" + intervalId + ", limit=" + limit + ", playsOnly=" + playsOnly);  // Debug
        List<LeaderboardDto> leaderboard = voteService.getLeaderboard(targetType, genreId, jurisdictionId, intervalId, limit);  // Pass playsOnly to service
        return ResponseEntity.ok(leaderboard);
}
}