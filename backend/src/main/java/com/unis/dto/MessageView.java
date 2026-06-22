package com.unis.dto;

import com.unis.entity.Message;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wire shape for a single message, used for both REST responses and STOMP
 * pushes over /user/queue/messages.
 */
public record MessageView(
        UUID id,
        UUID conversationId,
        UUID senderId,
        String body,
        UUID sharedSongId,
        Long supportPaymentId,
        LocalDateTime createdAt,
        boolean read
) {
    public static MessageView from(Message m) {
        return new MessageView(
                m.getId(),
                m.getConversationId(),
                m.getSenderId(),
                m.getBody(),
                m.getSharedSongId(),
                m.getSupportPaymentId(),
                m.getCreatedAt(),
                m.getReadAt() != null
        );
    }
}