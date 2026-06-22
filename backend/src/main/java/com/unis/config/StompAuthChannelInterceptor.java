package com.unis.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Authenticates the STOMP CONNECT frame the same way JwtRequestFilter
 * authenticates REST requests: read "Authorization: Bearer <jwt>", validate via
 * JwtUtil, and bind the userId claim as the session Principal.
 *
 * Because the Principal's name is the userId, SimpMessagingTemplate
 * .convertAndSendToUser(userId, "/queue/messages", ...) routes only to that
 * user's socket sessions.
 *
 * The browser can't set custom headers on the WebSocket handshake, so the JWT
 * is passed as a STOMP CONNECT native header (set by the frontend stomp client),
 * not on the HTTP upgrade.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtUtil jwtUtil;

    public StompAuthChannelInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new IllegalArgumentException("Missing or malformed Authorization header.");
            }

            String token = authHeader.substring(7);
            if (!Boolean.TRUE.equals(jwtUtil.validateToken(token))) {
                throw new IllegalArgumentException("Invalid or expired token.");
            }

            String userId = jwtUtil.extractClaim(token, claims -> claims.get("userId", String.class));
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("Token missing userId claim.");
            }

            // Principal name == userId → drives convertAndSendToUser routing.
            accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        }

        return message;
    }
}