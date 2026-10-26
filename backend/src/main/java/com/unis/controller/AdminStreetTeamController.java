package com.unis.controller;

import com.unis.service.StreetTeamService;
import com.unis.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Admin → Street team. Admin and super admin (the /api/v1/admin/** rule).
 *
 *   GET   /api/v1/admin/team/members                       everyone who joined, with phone, codes and counts
 *   PATCH /api/v1/admin/team/members/{userId}              { status: active | paused }
 *   GET   /api/v1/admin/team/meetups                       upcoming first, then past
 *   POST  /api/v1/admin/team/meetups                       { title, meetingPoint, startsAt, endsAt, side, spots, notes }
 *   GET   /api/v1/admin/team/meetups/{id}                  replies, attendance, sign-ups during it
 *   PATCH /api/v1/admin/team/meetups/{id}                  edit, or { status: "cancelled" } (emails everyone going)
 *   POST  /api/v1/admin/team/meetups/{id}/invite           { scope: match | all } → { sent, skipped }
 *   PATCH /api/v1/admin/team/meetups/{id}/attendance       { userId, attended }
 *
 * Times from the admin form ("2026-11-07T12:00") are New York time.
 */
@RestController
@RequestMapping("/api/v1/admin/team")
public class AdminStreetTeamController {

    private static final Logger log = LoggerFactory.getLogger(AdminStreetTeamController.class);

    private final StreetTeamService team;

    public AdminStreetTeamController(StreetTeamService team) {
        this.team = team;
    }

    @GetMapping("/members")
    public ResponseEntity<?> members() {
        return ResponseEntity.ok(Map.of("members", team.members()));
    }

    @PatchMapping("/members/{userId}")
    public ResponseEntity<?> memberStatus(@PathVariable UUID userId, @RequestBody Map<String, Object> body) {
        try {
            team.setMemberStatus(userId, String.valueOf(body.get("status")));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/meetups")
    public ResponseEntity<?> meetups() {
        return ResponseEntity.ok(Map.of("meetups", team.meetups()));
    }

    @PostMapping("/meetups")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(team.createMeetup(body, SecurityUtils.getAuthenticatedUserId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/meetups/{id}")
    public ResponseEntity<?> detail(@PathVariable UUID id) {
        return team.meetupDetail(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "No such meetup.")));
    }

    @PatchMapping("/meetups/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(team.updateMeetup(id, body, SecurityUtils.getAuthenticatedUserId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/meetups/{id}/invite")
    public ResponseEntity<?> invite(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        String scope = body == null ? "match" : String.valueOf(body.getOrDefault("scope", "match"));
        try {
            return ResponseEntity.ok(team.invite(id, scope, SecurityUtils.getAuthenticatedUserId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[StreetTeam] action=invite status=error meetup={} err={}", id, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "The invites couldn't be sent. Try again."));
        }
    }

    @PatchMapping("/meetups/{id}/attendance")
    public ResponseEntity<?> attendance(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        try {
            UUID userId = UUID.fromString(String.valueOf(body.get("userId")));
            team.setAttendance(id, userId, Boolean.TRUE.equals(body.get("attended")));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "Bad request." : e.getMessage()));
        }
    }
}
