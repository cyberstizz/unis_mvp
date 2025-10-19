package com.unis.controller;

import com.unis.entity.Award;
import com.unis.entity.Vote;
import com.unis.repository.AwardRepository;
import com.unis.service.VoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vote")
public class VoteController {
    @Autowired
    private VoteService voteService;

    @Autowired
    private AwardRepository awardRepository;

    // POST /api/v1/vote/submit (submit vote, page 2)
    @PostMapping("/submit")
    public ResponseEntity<Vote> submitVote(@RequestBody Vote vote) {
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

    // GET /api/v1/vote/leaderboards?type={type}&intervalId={id}&jurisdictionId={id} (leaderboards, page 4)
    @GetMapping("/leaderboards")
    public ResponseEntity<List<Award>> getLeaderboards(
            @RequestParam String type,
            @RequestParam(required = false) UUID intervalId,
            @RequestParam(required = false) UUID jurisdictionId) {
        LocalDate start = LocalDate.now().minusDays(30);  // Example range
        LocalDate end = LocalDate.now();
        List<Award> awards = awardRepository.findTopByPeriod(jurisdictionId, intervalId, start, end);
        return ResponseEntity.ok(awards);
    }

    // GET /api/v1/vote/votes/user/{userId} (votes cast by user, for score)
    @GetMapping("/votes/user/{userId}")
    public ResponseEntity<Long> getVotesCastByUser(@PathVariable UUID userId) {
        Long count = voteService.getVotesCastByUser(userId);
        return ResponseEntity.ok(count);
    }
}