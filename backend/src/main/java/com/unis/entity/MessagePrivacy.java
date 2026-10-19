package com.unis.entity;

/**
 * Who may start a new conversation with this user ("Who can message you" in
 * message settings). Existing conversations are never cut off by this.
 *
 * EVERYONE       Anyone may write. People with no link land in Requests. Default.
 * PEOPLE_I_KNOW  Only people this user follows, and people who support them.
 *                Everyone else is refused.
 * NO_ONE         Nobody new. The user can still start conversations themselves.
 */
public enum MessagePrivacy {
    EVERYONE,
    PEOPLE_I_KNOW,
    NO_ONE;

    /** Reading the legacy allow_messages_from_anyone flag (mobile build). */
    public static MessagePrivacy fromLegacy(Boolean allowFromAnyone) {
        return Boolean.FALSE.equals(allowFromAnyone) ? PEOPLE_I_KNOW : EVERYONE;
    }
}
