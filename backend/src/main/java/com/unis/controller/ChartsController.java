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
 * Returns this calendar month's top-voted songs for the jurisdiction
 * (including child jurisdictions), each with vote count and rank
 * movement versus the previous calendar month, plus the total number
 * of votes cast in the jurisdiction this month.
 */
@RestController
@RequestMapping("/api/v1/charts")
public class ChartsController {

    private final ChartsService chartsService;

    public ChartsController(ChartsService chartsService) {
        this.chartsService = chartsService;
    }

    @GetMapping
    public ResponseEntity<ChartsDto> getMonthlyChart(
            @RequestParam UUID jurisdictionId,
            @RequestParam(defaultValue = "10") int limit) {

        return ResponseEntity.ok(chartsService.getMonthlyChart(jurisdictionId, Math.min(limit, 50)));
    }
}