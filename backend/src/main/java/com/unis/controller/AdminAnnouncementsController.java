package com.unis.controller;

import com.unis.service.AwardCardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Admin → Announcements. Admin and super admin (the /api/v1/admin/** rule).
 *
 *   GET /api/v1/admin/announcements?date=YYYY-MM-DD
 *       every winner card for awards whose period ended that day (default:
 *       yesterday in Harlem), with a ready-written caption, for posting.
 */
@RestController
@RequestMapping("/api/v1/admin/announcements")
public class AdminAnnouncementsController {

    private final AwardCardService cards;

    public AdminAnnouncementsController(AwardCardService cards) {
        this.cards = cards;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String date) {
        LocalDate day;
        try {
            day = date == null || date.isBlank() ? AwardCardService.yesterday() : LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Use a date like 2026-11-21."));
        }
        return ResponseEntity.ok(Map.of("date", day.toString(), "cards", cards.announcements(day)));
    }
}
