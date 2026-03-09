package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "account_suspensions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountSuspension {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(name = "suspension_id")
    private UUID suspensionId;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "suspended_by", nullable = false)
    private User suspendedBy;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reason;

    @Column(name = "suspension_type", nullable = false)
    private String suspensionType; // temporary, permanent

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "lifted_at")
    private LocalDateTime liftedAt;

    @ManyToOne
    @JoinColumn(name = "lifted_by")
    private User liftedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}