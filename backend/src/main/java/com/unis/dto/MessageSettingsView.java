package com.unis.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** GET/PUT /api/v1/messages/settings — the "Messages" block in settings. */
public record MessageSettingsView(
        String messagePrivacy,          // EVERYONE | PEOPLE_I_KNOW | NO_ONE
        boolean supportersSkipRequests,
        boolean showActivityStatus,
        boolean sendReadReceipts,
        boolean filterSpamRequests,
        List<Blocked> blocked
) {
    public record Blocked(UUID userId, String username, String photoUrl, LocalDateTime blockedAt) {}
}
