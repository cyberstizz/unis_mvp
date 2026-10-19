package com.unis.dto;

import com.unis.entity.Message;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Wire shape for a single message, used for both REST responses and STOMP
 * pushes over /user/queue/messages.
 *
 *   read       the other person has read it — only ever true when receipts are
 *              on for both of you and the conversation is accepted
 *   reactions  one per person, oldest first
 *   request    sent as a message request (no sound, no badge on the receiving side)
 */
public record MessageView(
        UUID id,
        UUID conversationId,
        UUID senderId,
        String body,
        UUID sharedSongId,
        Long supportPaymentId,
        LocalDateTime createdAt,
        boolean read,
        List<Reaction> reactions,
        boolean request
) {
    public record Reaction(UUID userId, String emoji) {}

    /** Plain view: no reactions, not a request (artist broadcasts). */
    public static MessageView from(Message m) {
        return from(m, m.getReadAt() != null, List.of(), false);
    }

    public static MessageView from(Message m, boolean read, List<Reaction> reactions, boolean request) {
        return new MessageView(
                m.getId(),
                m.getConversationId(),
                m.getSenderId(),
                m.getBody(),
                m.getSharedSongId(),
                m.getSupportPaymentId(),
                m.getCreatedAt(),
                read,
                reactions == null ? List.of() : reactions,
                request
        );
    }
}
