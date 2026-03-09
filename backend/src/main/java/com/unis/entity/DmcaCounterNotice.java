package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "dmca_counter_notices")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DmcaCounterNotice {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(name = "counter_notice_id")
    private UUID counterNoticeId;

    @ManyToOne
    @JoinColumn(name = "claim_id", nullable = false)
    private DmcaClaim claim;

    @ManyToOne
    @JoinColumn(name = "respondent_user_id", nullable = false)
    private User respondentUser;

    @Column(name = "respondent_name", nullable = false)
    private String respondentName;

    @Column(name = "respondent_email", nullable = false)
    private String respondentEmail;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String statement;

    @Column(name = "consent_to_jurisdiction", nullable = false)
    private Boolean consentToJurisdiction;

    @Column(nullable = false)
    private String signature;

    @Column(nullable = false)
    private String status; // filed, waiting_period, content_restored, lawsuit_filed

    @Column(name = "filed_at", nullable = false)
    private LocalDateTime filedAt;

    @Column(name = "restore_eligible_at")
    private LocalDateTime restoreEligibleAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    protected void onCreate() {
        if (filedAt == null) filedAt = LocalDateTime.now();
        if (status == null) status = "filed";
    }
}