package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One user blocking another. A block in either direction prevents messaging
 * between the pair.
 */
@Entity
@Table(name = "user_blocks", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"blocker_id", "blocked_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserBlock {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "blocker_id", nullable = false)
    private UUID blockerId;

    @Column(name = "blocked_id", nullable = false)
    private UUID blockedId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}