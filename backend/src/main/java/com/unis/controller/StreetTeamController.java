package com.unis.controller;

import com.unis.service.StreetTeamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The street team, public side. No account login: members are known by the
 * private token in their email links (see StreetTeamService).
 *
 *   GET  /api/v1/team/info                     { signupCode, minAge }
 *   POST /api/v1/team/join                     the join form → always { ok: true }
 *   POST /api/v1/team/confirm   { t }          confirms the email → the member's page
 *   GET  /api/v1/team/me?t=TOKEN               the member's page: code, meetups, answers
 *   POST /api/v1/team/reply     { t, meetupId, answer: going | not_going }
 *   POST /api/v1/team/emails    { t, on }      street team emails on or off
 */
@RestController
@RequestMapping("/api/v1/team")
public class StreetTeamController {

    private static final Logger log = LoggerFactory.getLogger(StreetTeamController.class);

    private final StreetTeamService team;

    public StreetTeamController(StreetTeamService team) {
        this.team = team;
    }

    @GetMapping("/info")
    public ResponseEntity<?> info() {
        return ResponseEntity.ok(team.info());
    }

    @PostMapping("/join")
    public ResponseEntity<?> join(@RequestBody Map<String, Object> body) {
        try {
            team.join(str(body.get("email")), str(body.get("name")), str(body.get("phone")),
                    list(body.get("roles")), list(body.get("availability")), str(body.get("side")),
                    Boolean.TRUE.equals(body.get("adult")), Boolean.TRUE.equals(body.get("acceptedRules")));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[StreetTeam] action=join status=error err={}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Something went wrong. Please try again."));
        }
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestBody Map<String, Object> body) {
        UUID token = uuid(body.get("t"));
        if (token == null) return expired();
        return team.confirm(token).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(StreetTeamController::expired);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestParam(name = "t", required = false) String t) {
        UUID token = uuid(t);
        if (token == null) return expired();
        return team.member(token).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(StreetTeamController::expired);
    }

    @PostMapping("/reply")
    public ResponseEntity<?> reply(@RequestBody Map<String, Object> body) {
        UUID token = uuid(body.get("t"));
        UUID meetupId = uuid(body.get("meetupId"));
        if (token == null || meetupId == null) return expired();
        try {
            return ResponseEntity.ok(team.reply(token, meetupId, str(body.get("answer"))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/emails")
    public ResponseEntity<?> emails(@RequestBody Map<String, Object> body) {
        UUID token = uuid(body.get("t"));
        if (token == null) return expired();
        try {
            return ResponseEntity.ok(team.setEmails(token, Boolean.TRUE.equals(body.get("on"))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static ResponseEntity<?> expired() {
        return ResponseEntity.status(404).body(Map.of("error", "This link doesn't work anymore. Open your newest street team email."));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object o) {
        if (o instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        return List.of();
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        try {
            return UUID.fromString(String.valueOf(o).trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
