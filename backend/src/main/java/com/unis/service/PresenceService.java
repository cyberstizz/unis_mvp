package com.unis.service;

import com.unis.entity.User;
import com.unis.repository.ConversationRepository;
import com.unis.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Active now" for messaging.
 *
 * A person is online while they have at least one open messaging socket
 * (STOMP session) — tracked here from Spring's connect/disconnect events.
 * When someone comes online or goes offline, everyone they have an accepted
 * conversation with is told over /user/queue/presence.
 *
 * Visibility is mutual (help center, "Can people tell when I have read a
 * message?"): you are shown as active only if your activity status is on, and
 * you only see others if yours is on too.
 *
 * Scaling note: the session table is in memory, like the simple STOMP broker
 * (see WebSocketConfig). One instance is the launch setup; moving to several
 * instances means moving this table to Redis along with the broker relay.
 */
@Service
@Slf4j
public class PresenceService {

    /** STOMP session id → user id. */
    private final Map<String, UUID> sessionOwner = new ConcurrentHashMap<>();
    /** User id → number of open sessions (tabs, devices). */
    private final Map<UUID, Integer> openSessions = new ConcurrentHashMap<>();

    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public PresenceService(ConversationRepository conversationRepository,
                           UserRepository userRepository,
                           SimpMessagingTemplate messagingTemplate) {
        this.conversationRepository = conversationRepository;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
    }

    // ── Socket lifecycle ────────────────────────────────────────────────────

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        UUID userId = userIdOf(event.getUser());
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        if (userId == null || sessionId == null) return;
        if (sessionOwner.putIfAbsent(sessionId, userId) != null) return;   // already counted

        int open = openSessions.merge(userId, 1, Integer::sum);
        if (open == 1) announce(userId);
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        UUID userId = sessionOwner.remove(event.getSessionId());
        if (userId == null) return;                                          // unknown or already handled

        Integer left = openSessions.computeIfPresent(userId, (id, n) -> n > 1 ? n - 1 : null);
        if (left == null) announce(userId);
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    public boolean isConnected(UUID userId) {
        return userId != null && openSessions.containsKey(userId);
    }

    /** Should {@code viewer} see {@code other} as Active now? Mutual. */
    public boolean isVisibleOnline(User other, User viewer) {
        return other != null && viewer != null
                && activityOn(other) && activityOn(viewer)
                && isConnected(other.getUserId());
    }

    /** Re-announce after someone flips their activity status setting. */
    public void republish(UUID userId) {
        announce(userId);
    }

    // ── Fan-out ─────────────────────────────────────────────────────────────

    /**
     * Tell this person's accepted conversation partners (who are online and
     * have activity status on) whether they should now see them as active.
     */
    private void announce(UUID userId) {
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) return;
            boolean visible = activityOn(user) && isConnected(userId);

            List<UUID> partnerIds = conversationRepository.findAcceptedPartnerIds(userId);
            if (partnerIds.isEmpty()) return;

            Map<String, Object> payload = Map.of("userId", userId, "online", visible);
            for (User partner : userRepository.findAllById(partnerIds)) {
                if (!isConnected(partner.getUserId()) || !activityOn(partner)) continue;
                messagingTemplate.convertAndSendToUser(partner.getUserId().toString(), "/queue/presence", payload);
            }
        } catch (Exception e) {
            // Presence is a nicety; never let it break a socket connect/disconnect.
            log.warn("Presence announce for {} failed: {}", userId, e.getMessage());
        }
    }

    private static boolean activityOn(User u) {
        return !Boolean.FALSE.equals(u.getShowActivityStatus());
    }

    private static UUID userIdOf(Principal principal) {
        if (principal == null || principal.getName() == null) return null;
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
