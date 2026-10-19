package com.unis.controller;

import com.unis.service.MessagingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Client → server STOMP frames for messaging (the /app prefix in WebSocketConfig).
 *
 *   SEND /app/typing  { conversationId, typing: true|false }
 *     → relayed to the other participant on /user/queue/typing
 *
 * The Principal is the userId bound by StompAuthChannelInterceptor at CONNECT,
 * so a client can only ever type as itself. Presence needs no frame here — it
 * comes from socket connect/disconnect (PresenceService).
 */
@Controller
@Slf4j
public class MessagingSocketController {

    private final MessagingService messagingService;

    public MessagingSocketController(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    @MessageMapping("/typing")
    public void typing(@Payload Map<String, Object> body, Principal principal) {
        if (principal == null || body == null || body.get("conversationId") == null) return;
        try {
            UUID senderId = UUID.fromString(principal.getName());
            UUID conversationId = UUID.fromString(String.valueOf(body.get("conversationId")));
            boolean typing = Boolean.parseBoolean(String.valueOf(body.getOrDefault("typing", true)));
            messagingService.relayTyping(senderId, conversationId, typing);
        } catch (Exception e) {
            // Typing is best-effort; a bad frame is simply dropped.
            log.debug("Typing frame dropped: {}", e.getMessage());
        }
    }
}
