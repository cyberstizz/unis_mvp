package com.unis.config;

import com.unis.entity.ArtistTeamMember;
import com.unis.entity.User;
import com.unis.repository.UserRepository;
import com.unis.service.ArtistTeamService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Lets an artist's team member work on the artist's page with their own account.
 *
 * The dashboard sends "X-Unis-Act-As: <artistId>" while a member has picked an
 * artist. For the requests listed in RULES only, and only for an ACCEPTED
 * member, this filter runs the request as the artist: every existing endpoint
 * that reads the signed-in user (SecurityUtils.getAuthenticatedUserId) then
 * sees the artist, so uploads, edits and stats work unchanged.
 *
 * Everything not in RULES ignores the header and runs as the member themself:
 * likes, plays, votes, comments, follows, messages (except the supporters
 * broadcast), payouts and cash-out, Stripe onboarding, password, phone,
 * account deletion, supported artist, theme, team management. A member never
 * gets the artist's staff powers either: the acting identity carries no roles.
 *
 * VIEWERs may only read (GET). Every change a member makes is written to the
 * artist's team activity.
 *
 * Not a Spring @Component (so it isn't also registered as a plain servlet
 * filter); SecurityConfig adds it after JwtRequestFilter.
 */
public class TeamActAsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TeamActAsFilter.class);

    public static final String HEADER = "X-Unis-Act-As";

    /** One allowed request shape. activity null = reading, nothing to log. */
    private record Rule(Set<String> methods, Pattern path, String activity) {}

    private static Rule r(String methods, String regex, String activity) {
        return new Rule(Set.of(methods.split(",")), Pattern.compile("^/api" + regex + "$"), activity);
    }

    private static final String ID = "[0-9a-fA-F-]{36}";

    static final List<Rule> RULES = List.of(
        // Reading the dashboard
        r("GET", "/v1/users/profile-summary/" + ID, null),
        r("GET", "/v1/artist-analytics/.*", null),
        r("GET", "/v1/earnings/my-summary", null),
        r("GET", "/v1/stripe/(status|payouts)", null),
        r("GET", "/v1/songs/my-sales", null),
        r("GET", "/v1/releases(/.*)?", null),
        r("GET", "/v1/release-alerts/reach", null),
        // Music
        r("POST", "/v1/media/song", "uploaded a song"),
        r("POST", "/v1/media/video", "uploaded a video"),
        r("PATCH", "/v1/media/song/" + ID, "edited a song"),
        r("DELETE", "/v1/media/song/" + ID, "removed a song"),
        r("DELETE", "/v1/media/video/" + ID, "removed a video"),
        r("PATCH", "/v1/media/song/" + ID + "/lyrics", "edited lyrics"),
        r("PATCH", "/v1/media/song/" + ID + "/discovery", "edited a song's sound and mood"),
        r("PUT", "/v1/songs/" + ID + "/download-settings", "changed download settings"),
        // Releases (ReleaseService logs these with the title)
        r("POST", "/v1/releases", ""),
        r("PATCH", "/v1/releases/" + ID, ""),
        r("DELETE", "/v1/releases/" + ID, ""),
        // Profile, photos, featured song
        r("PATCH", "/v1/users/profile", "edited the profile"),
        r("PATCH", "/v1/users/profile/photo", "changed the profile photo"),
        r("PUT", "/v1/users/profile/" + ID, "edited the profile"),
        r("PUT", "/v1/users/profile/" + ID + "/bio", "edited the bio"),
        r("PUT", "/v1/users/profile/" + ID + "/photo", "changed the profile photo"),
        r("PATCH", "/v1/users/default-song", "changed the featured song"),
        r("POST", "/v1/users/" + ID + "/photos", "added a photo"),
        r("DELETE", "/v1/users/" + ID + "/photos/" + ID, "removed a photo"),
        // Supporters
        r("POST", "/v1/messages/broadcast", "sent a message to supporters")
    );

    private final ArtistTeamService teamService;
    private final UserRepository userRepository;
    private final AuthUserCache authUserCache;

    public TeamActAsFilter(ArtistTeamService teamService, UserRepository userRepository, AuthUserCache authUserCache) {
        this.teamService = teamService;
        this.userRepository = userRepository;
        this.authUserCache = authUserCache;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        Authentication original = SecurityContextHolder.getContext().getAuthentication();
        if (header == null || header.isBlank() || original == null || original.getCredentials() == null) {
            chain.doFilter(request, response);
            return;
        }

        String method = request.getMethod().toUpperCase();
        String path = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) path = path.substring(ctx.length());

        Rule rule = match(method, path);
        if (rule == null) {
            // Not a dashboard request: runs as the member themself.
            chain.doFilter(request, response);
            return;
        }

        UUID memberId;
        UUID artistId;
        try {
            memberId = UUID.fromString(String.valueOf(original.getCredentials()));
            artistId = UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            refuse(response, 400, "That artist couldn't be found.", "BAD_ACT_AS");
            return;
        }
        if (memberId.equals(artistId)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<ArtistTeamMember.Role> role = teamService.activeRole(artistId, memberId);
        if (role.isEmpty()) {
            refuse(response, 403, "You're not on this artist's team anymore.", "NOT_ON_TEAM");
            return;
        }
        if (role.get() == ArtistTeamMember.Role.VIEWER && !"GET".equals(method)) {
            refuse(response, 403, "You have view-only access to this page.", "VIEW_ONLY");
            return;
        }

        User artist = userRepository.findById(artistId).orElse(null);
        Optional<UserDetails> artistDetails = artist == null || artist.isDeleted() || artist.getRole() != User.Role.artist
                ? Optional.empty() : authUserCache.find(artist.getEmail());
        if (artistDetails.isEmpty()) {
            refuse(response, 403, "This artist's page isn't available.", "ARTIST_UNAVAILABLE");
            return;
        }

        String memberName = original.getPrincipal() instanceof UserDetails ud
                ? userRepository.findById(memberId).map(User::getUsername).orElse(ud.getUsername())
                : "A team member";

        // The artist's identity, with no roles: a member never gets staff powers.
        UsernamePasswordAuthenticationToken acting =
                new UsernamePasswordAuthenticationToken(artistDetails.get(), artistId.toString(), List.of());
        acting.setDetails(original.getDetails());

        request.setAttribute(ArtistTeamService.ACTOR_ATTR, memberId);
        request.setAttribute(ArtistTeamService.ACTOR_NAME_ATTR, memberName);
        SecurityContextHolder.getContext().setAuthentication(acting);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(original);
        }

        if (rule.activity() != null && !rule.activity().isEmpty() && response.getStatus() < 400) {
            teamService.log(artistId, memberId, rule.activity());
        }
        if (!"GET".equals(method)) {
            log.info("[Team] {} {} artist={} member={} status={}", method, path, artistId, memberId, response.getStatus());
        }
    }

    static Rule match(String method, String path) {
        for (Rule rule : RULES) {
            if (rule.methods().contains(method) && rule.path().matcher(path).matches()) return rule;
        }
        return null;
    }

    private static void refuse(HttpServletResponse response, int status, String message, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\",\"code\":\"" + code + "\"}");
    }
}
