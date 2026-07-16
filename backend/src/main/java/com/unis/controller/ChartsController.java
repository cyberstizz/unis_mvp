package com.unis.controller;

import com.unis.dto.ChartsDto;
import com.unis.service.ChartsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public, read-only chart data for the Feed "Charts" lens.
 *
 * GET /api/v1/charts?jurisdictionId={uuid}&limit=10
 *
 * Returns the most played songs of the last 7 days for the jurisdiction
 * (including child jurisdictions), each with play count and rank
 * movement versus the previous 7-day window, plus the total number
 * of plays in the jurisdiction this week.
 */
@RestController
@RequestMapping("/api/v1/charts")
public class ChartsController {

    private final ChartsService chartsService;

    public ChartsController(ChartsService chartsService) {
        this.chartsService = chartsService;
    }

    @GetMapping
    public ResponseEntity<ChartsDto> getWeeklyChart(
            @RequestParam UUID jurisdictionId,
            @RequestParam(defaultValue = "10") int limit) {

        return ResponseEntity.ok(chartsService.getWeeklyChart(jurisdictionId, Math.min(limit, 50)));
    }
}