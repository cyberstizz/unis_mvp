package com.unis.controller;

import com.unis.service.EarningsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/earnings")
public class EarningsController {
    @Autowired
    private EarningsService earningsService;

    // GET /api/v1/earnings/{artistId}?days=30 (page 9 daily totals)
    @GetMapping("/{artistId}")
    public ResponseEntity<List<Object>> getEarnings(@PathVariable UUID artistId, @RequestParam(defaultValue = "30") int days) {
        List<Object[]> earnings = earningsService.getEarningsByArtist(artistId, days);
        return ResponseEntity.ok((List<Object>) (Object) earnings);
    }

    // GET /api/v1/earnings/{artistId}/breakdown?days=30 (page 9 breakdown)
    @GetMapping("/{artistId}/breakdown")
    public ResponseEntity<Object> getEarningsBreakdown(@PathVariable UUID artistId, @RequestParam(defaultValue = "30") int days) {
        Object breakdown = earningsService.getEarningsBreakdown(artistId, days);
        return ResponseEntity.ok(breakdown);
    }
}