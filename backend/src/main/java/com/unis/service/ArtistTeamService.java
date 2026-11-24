package com.unis.service;

import com.unis.entity.ArtistTeamActivity;
import com.unis.entity.ArtistTeamMember;
import com.unis.entity.User;
import com.unis.repository.ArtistTeamActivityRepository;
import com.unis.repository.ArtistTeamMemberRepository;
import com.unis.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Artist teams: people who help run an artist's page with their own account.
 *
 * Rules (the help text and the dashboard say the same):
 *  - Only the artist invites, changes roles and removes people. Up to 5.
 *  - Invitees must have a verified phone, like voters and earners.
 *  - MANAGER: upload and edit songs, videos and releases, edit the profile,
 *    photos and bio, see stats and earnings, message supporters.
 *  - VIEWER: see only.
 *  - Nobody but the artist touches payouts, cashes out, reads the artist's
 *    messages, votes as them, or changes their sign-in details.
 * What a member may do as the artist is enforced in TeamActAsFilter.
 */
@Service
@Slf4j
public class ArtistTeamService {

    public static final int MAX_TEAM = 5;

    /** Request attribute set by TeamActAsFilter while a member acts for an artist. */
    public static final String ACTOR_ATTR = "unis.teamActor";
    /** Request attribute: the actor's username, for signing supporter messages. */
    public static final String ACTOR_NAME_ATTR = "unis.teamActorName";

    public record MemberView(UUID memberId, String username, String photoUrl, String role, String status,
                             LocalDateTime createdAt, LocalDateTime acceptedAt) {}
    public record ManagedView(UUID artistId, String username, String photoUrl, String role) {}
    public record InviteView(UUID inviteId, UUID artistId, String username, String photoUrl, String role,
                             LocalDateTime createdAt) {}
    public record ActivityView(UUID actorId, String actorName, String action, LocalDateTime createdAt) {}

    private final ArtistTeamMemberRepository members;
    private final ArtistTeamActivityRepository activity;
    private final UserRepository users;

    public ArtistTeamService(ArtistTeamMemberRepository members, ArtistTeamActivityRepository activity,
                             UserRepository users) {
        this.members = members;
        this.activity = activity;
        this.users = users;
    }

    // ── Who is acting right now ─────────────────────────────────────────────

    /** The team member acting for an artist on this request, if any (set by TeamActAsFilter). */
    public static Optional<UUID> currentActor() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) return Optional.empty();
        Object v = attrs.getAttribute(ACTOR_ATTR, RequestAttributes.SCOPE_REQUEST);
        return v instanceof UUID u ? Optional.of(u) : Optional.empty();
    }

    public static Optional<String> currentActorName() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) return Optional.empty();
        Object v = attrs.getAttribute(ACTOR_NAME_ATTR, RequestAttributes.SCOPE_REQUEST);
        return v instanceof String s ? Optional.of(s) : Optional.empty();
    }

    /** The accepted role this member has on this artist's team, if any. */
    @Transactional(readOnly = true)
    public Optional<ArtistTeamMember.Role> activeRole(UUID artistId, UUID memberId) {
        return members.findByArtistIdAndMemberId(artistId, memberId)
                .filter(m -> m.getStatus() == ArtistTeamMember.Status.ACCEPTED)
                .map(ArtistTeamMember::getRole);
    }

    /** Record a change on the artist's page. Never fails the request it describes. */
    public void log(UUID artistId, UUID actorId, String action) {
        try {
            activity.save(ArtistTeamActivity.builder()
                    .artistId(artistId).actorId(actorId)
                    .action(action.length() > 240 ? action.substring(0, 240) : action)
                    .build());
        } catch (Exception e) {
            log.warn("[Team] activity not recorded artist={} actor={}: {}", artistId, actorId, e.getMessage());
        }
    }

    /** Log only when a team member (not the artist) made the change. */
    public void logIfActing(UUID artistId, String action) {
        currentActor().ifPresent(actor -> log(artistId, actor, action));
    }

    // ── The artist's side ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MemberView> members(UUID artistId) {
        List<ArtistTeamMember> rows = members.findByArtistIdOrderByCreatedAtAsc(artistId);
        Map<UUID, User> byId = usersById(rows.stream().map(ArtistTeamMember::getMemberId).toList());
        List<MemberView> out = new ArrayList<>();
        for (ArtistTeamMember m : rows) {
            User u = byId.get(m.getMemberId());
            if (u == null || u.isDeleted()) continue;
            out.add(new MemberView(m.getMemberId(), u.getUsername(), u.getPhotoUrl(), m.getRole().name(),
                    m.getStatus().name(), m.getCreatedAt(), m.getAcceptedAt()));
        }
        return out;
    }

    @Transactional
    public MemberView invite(UUID artistId, String username, String roleName) {
        User artist = requireArtist(artistId);
        ArtistTeamMember.Role role = parseRole(roleName);
        String name = username == null ? "" : username.trim().replaceFirst("^@", "");
        if (name.isEmpty()) throw bad("Enter a username.");

        User target = members.findLiveUserByUsername(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No one on Unis goes by @" + name + "."));
        if (target.getUserId().equals(artistId)) throw bad("That's you. You're already the owner.");
        if (!Boolean.TRUE.equals(target.getPhoneVerified())) {
            throw bad("@" + target.getUsername() + " needs to verify their phone before joining a team.");
        }
        if (members.findByArtistIdAndMemberId(artistId, target.getUserId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "@" + target.getUsername() + " is already on your team or invited.");
        }
        if (members.countByArtistId(artistId) >= MAX_TEAM) {
            throw bad("Your team is full (" + MAX_TEAM + " people). Remove someone first.");
        }

        ArtistTeamMember saved = members.save(ArtistTeamMember.builder()
                .artistId(artistId).memberId(target.getUserId()).role(role)
                .status(ArtistTeamMember.Status.PENDING).invitedBy(artistId).build());
        log(artistId, artistId, "invited " + target.getUsername() + " as " + label(role));
        log.info("[Team] invite artist={} member={} role={}", artistId, target.getUserId(), role);
        return new MemberView(target.getUserId(), target.getUsername(), target.getPhotoUrl(), role.name(),
                saved.getStatus().name(), saved.getCreatedAt(), null);
    }

    @Transactional
    public void changeRole(UUID artistId, UUID memberId, String roleName) {
        requireArtist(artistId);
        ArtistTeamMember m = members.findByArtistIdAndMemberId(artistId, memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "They're not on your team."));
        ArtistTeamMember.Role role = parseRole(roleName);
        if (m.getRole() == role) return;
        m.setRole(role);
        members.save(m);
        log(artistId, artistId, "made " + nameOf(memberId) + " a " + label(role));
    }

    @Transactional
    public void remove(UUID artistId, UUID memberId) {
        ArtistTeamMember m = members.findByArtistIdAndMemberId(artistId, memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "They're not on your team."));
        boolean wasPending = m.getStatus() == ArtistTeamMember.Status.PENDING;
        members.delete(m);
        log(artistId, artistId, (wasPending ? "cancelled the invite for " : "removed ") + nameOf(memberId));
        log.info("[Team] removed artist={} member={}", artistId, memberId);
    }

    @Transactional(readOnly = true)
    public List<ActivityView> activity(UUID artistId) {
        List<ArtistTeamActivity> rows = activity.findByArtistIdOrderByCreatedAtDesc(artistId, PageRequest.of(0, 100));
        Map<UUID, User> byId = usersById(rows.stream().map(ArtistTeamActivity::getActorId).filter(java.util.Objects::nonNull).toList());
        return rows.stream().map(a -> {
            User u = a.getActorId() == null ? null : byId.get(a.getActorId());
            String who = a.getActorId() != null && a.getActorId().equals(artistId) ? "You"
                    : (u == null ? "Someone" : u.getUsername());
            return new ActivityView(a.getActorId(), who, a.getAction(), a.getCreatedAt());
        }).toList();
    }

    // ── The member's side ───────────────────────────────────────────────────

    /** Pages this person helps run, and invites waiting for them. */
    @Transactional(readOnly = true)
    public Map<String, Object> mine(UUID memberId) {
        List<ArtistTeamMember> accepted = members.findByMemberIdAndStatusOrderByCreatedAtDesc(memberId, ArtistTeamMember.Status.ACCEPTED);
        List<ArtistTeamMember> pending = members.findByMemberIdAndStatusOrderByCreatedAtDesc(memberId, ArtistTeamMember.Status.PENDING);
        List<UUID> ids = new ArrayList<>();
        accepted.forEach(m -> ids.add(m.getArtistId()));
        pending.forEach(m -> ids.add(m.getArtistId()));
        Map<UUID, User> byId = usersById(ids);

        List<ManagedView> managing = new ArrayList<>();
        for (ArtistTeamMember m : accepted) {
            User a = byId.get(m.getArtistId());
            if (a == null || a.isDeleted()) continue;
            managing.add(new ManagedView(a.getUserId(), a.getUsername(), a.getPhotoUrl(), m.getRole().name()));
        }
        List<InviteView> invites = new ArrayList<>();
        for (ArtistTeamMember m : pending) {
            User a = byId.get(m.getArtistId());
            if (a == null || a.isDeleted()) continue;
            invites.add(new InviteView(m.getId(), a.getUserId(), a.getUsername(), a.getPhotoUrl(), m.getRole().name(), m.getCreatedAt()));
        }
        return Map.of("managing", managing, "invites", invites);
    }

    @Transactional
    public ManagedView accept(UUID inviteId, UUID memberId) {
        ArtistTeamMember m = ownInvite(inviteId, memberId);
        if (m.getStatus() != ArtistTeamMember.Status.ACCEPTED) {
            m.setStatus(ArtistTeamMember.Status.ACCEPTED);
            m.setAcceptedAt(LocalDateTime.now());
            members.save(m);
            log(m.getArtistId(), memberId, "joined the team as " + label(m.getRole()));
        }
        User a = users.findById(m.getArtistId()).orElseThrow();
        return new ManagedView(a.getUserId(), a.getUsername(), a.getPhotoUrl(), m.getRole().name());
    }

    @Transactional
    public void decline(UUID inviteId, UUID memberId) {
        ArtistTeamMember m = ownInvite(inviteId, memberId);
        members.delete(m);
        log(m.getArtistId(), memberId, m.getStatus() == ArtistTeamMember.Status.PENDING ? "declined the invite" : "left the team");
    }

    /** A member leaves a team they're on. */
    @Transactional
    public void leave(UUID artistId, UUID memberId) {
        ArtistTeamMember m = members.findByArtistIdAndMemberId(artistId, memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You're not on that team."));
        members.delete(m);
        log(artistId, memberId, "left the team");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ArtistTeamMember ownInvite(UUID inviteId, UUID memberId) {
        ArtistTeamMember m = members.findById(inviteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That invite is no longer open."));
        if (!m.getMemberId().equals(memberId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That invite is no longer open.");
        }
        return m;
    }

    private User requireArtist(UUID artistId) {
        User u = users.findById(artistId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (u.isDeleted() || u.getRole() != User.Role.artist) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only artists can have a team.");
        }
        return u;
    }

    private static ArtistTeamMember.Role parseRole(String roleName) {
        try {
            return ArtistTeamMember.Role.valueOf(String.valueOf(roleName).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw bad("Choose Manager or Viewer.");
        }
    }

    private static String label(ArtistTeamMember.Role role) {
        return role == ArtistTeamMember.Role.MANAGER ? "Manager" : "Viewer";
    }

    private String nameOf(UUID userId) {
        return users.findById(userId).map(User::getUsername).orElse("someone");
    }

    private Map<UUID, User> usersById(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return users.findAllById(ids).stream().collect(Collectors.toMap(User::getUserId, Function.identity(), (a, b) -> a));
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
