package com.unis.controller;

import com.unis.dto.PeriodLeaderboardDto;
import com.unis.entity.Award;
import com.unis.service.AwardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/awards")
public class AwardController {

    @Autowired
    private AwardService awardService;

    /**
     * GET /api/v1/awards/leaderboards
     * Current leaderboards for live rankings
     */
    @GetMapping("/leaderboards")
    public ResponseEntity<List<Award>> getLeaderboards(
            @RequestParam String type,
            @RequestParam(required = false) UUID intervalId,
            @RequestParam(required = false) UUID jurisdictionId) {
        List<Award> awards = awardService.getLeaderboards(type, intervalId, jurisdictionId);
        return ResponseEntity.ok(awards);
    }

    /**
     * GET /api/v1/awards/past
     * Past awards/milestones (legacy endpoint — winner only)
     */
    @GetMapping("/past")
    public ResponseEntity<List<Award>> getPastAwards(
            @RequestParam String type,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate,
            @RequestParam(required = false) UUID jurisdictionId,
            @RequestParam(required = false) UUID genreId,
            @RequestParam(required = false) UUID intervalId) {
        List<Award> awards = awardService.getPastAwards(type, startDate, endDate, jurisdictionId, genreId, intervalId);
        return ResponseEntity.ok(awards);
    }

    /**
     * GET /api/v1/awards/period-leaderboard
     * Returns the winner + top N candidates for a specific period.
     * Backs the redesigned Milestones page.
     */
    @GetMapping("/period-leaderboard")
    public ResponseEntity<PeriodLeaderboardDto> getPeriodLeaderboard(
            @RequestParam String type,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate,
            @RequestParam UUID jurisdictionId,
            @RequestParam UUID genreId,
            @RequestParam UUID intervalId,
            @RequestParam(defaultValue = "5") int limit) {
        PeriodLeaderboardDto result = awardService.getPeriodLeaderboard(
                type, startDate, endDate, jurisdictionId, genreId, intervalId, limit);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/v1/awards/cron/manual
     * Manually trigger award computation for a specific date (testing)
     */
    @GetMapping("/cron/manual")
    public ResponseEntity<String> manualCron(@RequestParam(required = false) String date) {
        LocalDate cronDate = date != null ? LocalDate.parse(date) : LocalDate.now().minusDays(1);
        awardService.computeDailyAwardsForDate(cronDate);
        return ResponseEntity.ok("Daily awards computed for " + cronDate);
    }

    /**
     * POST /api/v1/awards/compute
     * Compute awards for a specific interval/jurisdiction/genre/date
     */
    @PostMapping("/compute")
    public ResponseEntity<String> computeAwards(
            @RequestParam UUID intervalId,
            @RequestParam(required = false) UUID jurisdictionId,
            @RequestParam(required = false) UUID genreId,
            @RequestParam(required = false) LocalDate date) {
        LocalDate cronDate = date != null ? date : LocalDate.now().minusDays(1);
        awardService.computeForInterval(intervalId, jurisdictionId, genreId, cronDate);
        return ResponseEntity.ok("Awards computed for interval " + intervalId + " on " + cronDate);
    }

    /**
     * POST /api/v1/awards/recompute-all
     * Recompute ALL historical awards from vote data
     * WARNING: This clears and recreates all awards!
     */
    @PostMapping("/recompute-all")
    public ResponseEntity<String> recomputeAllAwards() {
        awardService.recomputeAllHistoricalAwards();
        return ResponseEntity.ok("All historical awards recomputed from vote data");
    }

    /**
     * GET /api/v1/awards/winner
     * Get the single winner for a specific category/date
     */
    @GetMapping("/winner")
    public ResponseEntity<Award> getWinner(
            @RequestParam String type,
            @RequestParam UUID jurisdictionId,
            @RequestParam UUID genreId,
            @RequestParam UUID intervalId,
            @RequestParam LocalDate date) {
        List<Award> awards = awardService.getPastAwards(type, date, date, jurisdictionId, genreId, intervalId);
        if (awards.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(awards.get(0));
    }

    @GetMapping("/artist/{artistId}")
    public ResponseEntity<List<Award>> getArtistAwards(
            @PathVariable UUID artistId,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<Award> awards = awardService.getArtistAwards(artistId, limit, offset);
        return ResponseEntity.ok(awards);
    }

    @GetMapping("/artist/{artistId}/songs")
    public ResponseEntity<List<Award>> getArtistSongAwards(
            @PathVariable UUID artistId,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<Award> awards = awardService.getArtistSongAwards(artistId, limit, offset);
        return ResponseEntity.ok(awards);
    }
}