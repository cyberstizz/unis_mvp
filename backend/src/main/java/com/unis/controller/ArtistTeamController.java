package com.unis.controller;

import com.unis.service.ArtistTeamService;
import com.unis.util.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Artist teams (managers and viewers). Always the signed-in person's own
 * account: these routes are never reachable "as" someone else
 * (TeamActAsFilter ignores them), so only the artist manages their team.
 * (/api/v1/team is the street team's.)
 *
 * The artist:
 *   GET    /api/v1/artist-team/members
 *   POST   /api/v1/artist-team/invite                 { "username", "role": "MANAGER|VIEWER" }
 *   PATCH  /api/v1/artist-team/members/{memberId}     { "role" }
 *   DELETE /api/v1/artist-team/members/{memberId}     remove, or cancel an invite
 *   GET    /api/v1/artist-team/activity
 * The member:
 *   GET    /api/v1/artist-team/mine                   { managing: [...], invites: [...] }
 *   POST   /api/v1/artist-team/invites/{id}/accept
 *   POST   /api/v1/artist-team/invites/{id}/decline
 *   DELETE /api/v1/artist-team/teams/{artistId}       leave a team
 */
@RestController
@RequestMapping("/api/v1/artist-team")
public class ArtistTeamController {

    private final ArtistTeamService team;

    public ArtistTeamController(ArtistTeamService team) {
        this.team = team;
    }

    @GetMapping("/members")
    public ResponseEntity<?> members() {
        return run(() -> team.members(me()));
    }

    @PostMapping("/invite")
    public ResponseEntity<?> invite(@RequestBody Map<String, String> body) {
        return run(() -> team.invite(me(), body.get("username"), body.get("role")));
    }

    @PatchMapping("/members/{memberId}")
    public ResponseEntity<?> changeRole(@PathVariable UUID memberId, @RequestBody Map<String, String> body) {
        return run(() -> {
            team.changeRole(me(), memberId, body.get("role"));
            return Map.of("ok", true);
        });
    }

    @DeleteMapping("/members/{memberId}")
    public ResponseEntity<?> remove(@PathVariable UUID memberId) {
        return run(() -> {
            team.remove(me(), memberId);
            return Map.of("ok", true);
        });
    }

    @GetMapping("/activity")
    public ResponseEntity<?> activity() {
        return run(() -> team.activity(me()));
    }

    @GetMapping("/mine")
    public ResponseEntity<?> mine() {
        return run(() -> team.mine(me()));
    }

    @PostMapping("/invites/{inviteId}/accept")
    public ResponseEntity<?> accept(@PathVariable UUID inviteId) {
        return run(() -> team.accept(inviteId, me()));
    }

    @PostMapping("/invites/{inviteId}/decline")
    public ResponseEntity<?> decline(@PathVariable UUID inviteId) {
        return run(() -> {
            team.decline(inviteId, me());
            return Map.of("ok", true);
        });
    }

    @DeleteMapping("/teams/{artistId}")
    public ResponseEntity<?> leave(@PathVariable UUID artistId) {
        return run(() -> {
            team.leave(artistId, me());
            return Map.of("ok", true);
        });
    }

    private static UUID me() {
        return SecurityUtils.getAuthenticatedUserId();
    }

    private static ResponseEntity<?> run(Supplier<Object> body) {
        try {
            return ResponseEntity.ok(body.get());
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", String.valueOf(e.getReason())));
        }
    }
}
