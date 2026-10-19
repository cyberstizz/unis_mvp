package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A 1:1 conversation between two users. Participants are stored in a canonical
 * order (participantOne = the numerically-smaller UUID) so there is exactly one
 * row per pair, enforced by the unique constraint.
 *
 * Per-participant lastReadAt timestamps drive unread counts and read receipts
 * without a separate read-state table.
 */
@Entity
@Table(name = "conversations", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"participant_one", "participant_two"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Conversation {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "participant_one", nullable = false)
    private UUID participantOne;

    @Column(name = "participant_two", nullable = false)
    private UUID participantTwo;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "last_message_preview", length = 300)
    private String lastMessagePreview;

    @Column(name = "last_read_at_one")
    private LocalDateTime lastReadAtOne;

    @Column(name = "last_read_at_two")
    private LocalDateTime lastReadAtTwo;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── Lanes (sql/V2026_10f__messaging_lanes.sql) ─────────────────────────

    /** Who started the conversation. Decides which side sees it as a request. */
    @Column(name = "initiated_by")
    private UUID initiatedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private ConversationStatus status = ConversationStatus.ACCEPTED;

    /** True when it began in the recipient's Requests (counts toward the daily limit). */
    @Column(name = "started_as_request", nullable = false)
    @Builder.Default
    private Boolean startedAsRequest = false;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    /** Recipient deleted the request. Hidden from them; the sender is never told. */
    @Column(name = "declined_at")
    private LocalDateTime declinedAt;

    /** Request caught by the recipient's spam filter — shown under Filtered, not Requests. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean filtered = false;
}