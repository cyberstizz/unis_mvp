package com.unis.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wire shape for one inbox row: the conversation plus the *other* participant's
 * display info and the viewer's unread count.
 */
public record ConversationView(
        UUID id,
        UUID otherUserId,
        String otherUsername,
        String otherPhotoUrl,
        String lastMessagePreview,
        LocalDateTime lastMessageAt,
        long unreadCount
) {}