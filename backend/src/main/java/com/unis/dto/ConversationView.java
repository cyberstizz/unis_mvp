package com.unis.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wire shape for one inbox row, from the viewer's side: the conversation plus
 * the *other* participant's display info and the viewer's unread count.
 *
 * The first seven fields are the original shape (the mobile build reads them);
 * everything after them was added with the lanes (Oct 2026).
 *
 *   lane                 "chats" | "requests" | "filtered" — where it sits for the viewer
 *   initiatedByMe        the viewer started it
 *   pending              still a request (either side)
 *   otherRole            "artist" | "listener"
 *   otherSupportsMe      the other person supports the viewer (supporter ring)
 *   otherOnline          Active now — only when BOTH have activity status on
 *   lastMessageMine      the latest message is the viewer's ("You:" prefix)
 *   lastMessageSeen      ...and the other person has read it (mutual receipts)
 */
public record ConversationView(
        UUID id,
        UUID otherUserId,
        String otherUsername,
        String otherPhotoUrl,
        String lastMessagePreview,
        LocalDateTime lastMessageAt,
        long unreadCount,
        String lane,
        boolean initiatedByMe,
        boolean pending,
        String otherRole,
        String otherJurisdictionName,
        boolean otherSupportsMe,
        boolean otherOnline,
        boolean lastMessageMine,
        boolean lastMessageSeen
) {}
