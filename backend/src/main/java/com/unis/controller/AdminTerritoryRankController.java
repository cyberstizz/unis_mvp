package com.unis.controller;

import com.unis.service.TerritoryRankService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin-only manual trigger for the Territory Rank precompute.
 *
 * Mapped under /api/v1/admin/** which SecurityConfig already guards with
 * hasRole("ADMIN"), so no SecurityConfig change is needed. Use this once
 * right after deploy to populate jurisdiction_ranks immediately instead of
 * waiting for the 00:30 ET nightly run.
 *
 * The run is logged to cron_executions as TERRITORY_RANKS, same as the
 * scheduled run, so it shows up in the cron status dashboard.
 */
@RestController
@RequestMapping("/api/v1/admin/territory-ranks")
public class AdminTerritoryRankController {

    private final TerritoryRankService territoryRankService;

    public AdminTerritoryRankController(TerritoryRankService territoryRankService) {
        this.territoryRankService = territoryRankService;
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run() {
        long t0 = System.currentTimeMillis();
        int rows = territoryRankService.runNow();
        long ms = System.currentTimeMillis() - t0;
        return ResponseEntity.ok(Map.of(
            "status", "ok",
            "rowsWritten", rows,
            "durationMs", ms
        ));
    }
}