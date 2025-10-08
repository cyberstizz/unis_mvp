package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "referrals")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Referral {
    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID referralId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referrer_id")
    private User referrer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referred_id")
    private User referred;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}