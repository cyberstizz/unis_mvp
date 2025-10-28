package com.unis.controller;

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

    // GET /api/v1/awards/leaderboards?type={type}&intervalId={id}&jurisdictionId={id} (page 4 current leaderboards)
    @GetMapping("/leaderboards")
    public ResponseEntity<List<Award>> getLeaderboards(
            @RequestParam String type,  // "song" or "artist"
            @RequestParam(required = false) UUID intervalId,
            @RequestParam(required = false) UUID jurisdictionId) {
        List<Award> awards = awardService.getLeaderboards(type, intervalId, jurisdictionId);
        return ResponseEntity.ok(awards);
    }

    // GET /api/v1/awards/past?type={type}&startDate={date}&endDate={date}&jurisdictionId={id} (page 5 past milestones)
    @GetMapping("/past")
    public ResponseEntity<List<Award>> getPastAwards(
            @RequestParam String type,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate,
            @RequestParam(required = false) UUID jurisdictionId) {
        List<Award> awards = awardService.getPastAwards(type, startDate, endDate, jurisdictionId);
        return ResponseEntity.ok(awards);
    }

    // Temp for testing cron (manual for past dates; remove in prod)
    @GetMapping("/cron/manual")
    public ResponseEntity<String> manualCron(@RequestParam(required = false) String date) {
        LocalDate cronDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        awardService.computeDailyAwardsForDate(cronDate);  // Add method to service
        return ResponseEntity.ok("Cron triggered for " + cronDate + "—check DB awards");
    }
}