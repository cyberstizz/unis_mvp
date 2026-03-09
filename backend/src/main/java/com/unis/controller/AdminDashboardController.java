package com.unis.controller;

import com.unis.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/analytics")
public class AdminDashboardController {

    @Autowired
    private AnalyticsService analyticsService;

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview() {
        return ResponseEntity.ok(analyticsService.getOverview());
    }

    @GetMapping("/dau")
    public ResponseEntity<Map<String, Long>> getDailyActiveUsers(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (startDate == null) startDate = LocalDate.now().minusDays(30);
        if (endDate == null) endDate = LocalDate.now();
        return ResponseEntity.ok(analyticsService.getDailyActiveUsers(startDate, endDate));
    }

    @GetMapping("/mau")
    public ResponseEntity<Map<String, Long>> getMonthlyActiveUsers(
            @RequestParam(defaultValue = "6") int months) {
        return ResponseEntity.ok(analyticsService.getMonthlyActiveUsers(months));
    }

    @GetMapping("/signups")
    public ResponseEntity<Map<String, Long>> getNewSignups(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (startDate == null) startDate = LocalDate.now().minusDays(30);
        if (endDate == null) endDate = LocalDate.now();
        return ResponseEntity.ok(analyticsService.getNewSignups(startDate, endDate));
    }

    @GetMapping("/plays")
    public ResponseEntity<Map<String, Long>> getPlayCounts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (startDate == null) startDate = LocalDate.now().minusDays(30);
        if (endDate == null) endDate = LocalDate.now();
        return ResponseEntity.ok(analyticsService.getPlayCounts(startDate, endDate));
    }

    @GetMapping("/votes")
    public ResponseEntity<Map<String, Long>> getVotesByJurisdiction(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (startDate == null) startDate = LocalDate.now().minusDays(30);
        if (endDate == null) endDate = LocalDate.now();
        return ResponseEntity.ok(analyticsService.getVotesByJurisdiction(startDate, endDate));
    }

    @GetMapping("/referrals")
    public ResponseEntity<Map<String, Object>> getReferralStats() {
        return ResponseEntity.ok(analyticsService.getReferralStats());
    }

    @GetMapping("/dmca")
    public ResponseEntity<Map<String, Object>> getDmcaStats() {
        return ResponseEntity.ok(analyticsService.getDmcaStats());
    }
}