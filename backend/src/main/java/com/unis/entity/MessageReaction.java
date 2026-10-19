package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One emoji reaction on one message. A person holds at most one reaction per
 * message: reacting again with a different emoji replaces it, the same emoji
 * removes it (sql/V2026_10f__messaging_lanes.sql).
 */
@Entity
@Table(name = "message_reactions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"message_id", "user_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageReaction {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 16)
    private String emoji;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
