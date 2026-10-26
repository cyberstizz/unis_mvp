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

    // The exact code typed at sign-up: the referrer's own code or a short
    // code they own (see ReferralCodeService). Null on referrals made before
    // V2026_10k__launch_marketing.sql.
    @Column(name = "code_used", length = 64)
    private String codeUsed;
}