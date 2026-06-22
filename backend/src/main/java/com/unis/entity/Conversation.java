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
}