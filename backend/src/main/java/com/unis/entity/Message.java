package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single message in a conversation. Raw UUID/Long foreign keys (matching the
 * Purchase entity style) to keep the hot read path free of lazy-load joins.
 *
 * sharedSongId   → SoundCloud-style in-thread track share (nullable)
 * supportPaymentId → links a message to a SupportPayment when a tip is sent
 *                    inside the thread, so the "you sent support" bubble in the
 *                    mockup is just a message with this set (nullable)
 */
@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_conversation", columnList = "conversation_id, created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "shared_song_id")
    private UUID sharedSongId;

    @Column(name = "support_payment_id")
    private Long supportPaymentId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "read_at")
    private LocalDateTime readAt;
}