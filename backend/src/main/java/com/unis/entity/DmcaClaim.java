package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "dmca_claims")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DmcaClaim {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(name = "claim_id")
    private UUID claimId;

    @Column(name = "claimant_name", nullable = false)
    private String claimantName;

    @Column(name = "claimant_email", nullable = false)
    private String claimantEmail;

    @Column(name = "claimant_phone")
    private String claimantPhone;

    @Column(name = "claimant_company")
    private String claimantCompany;

    @Column(name = "copyright_owner", nullable = false)
    private String copyrightOwner;

    @Column(name = "work_description", columnDefinition = "TEXT", nullable = false)
    private String workDescription;

    @Column(name = "original_work_url", length = 512)
    private String originalWorkUrl;

    @ManyToOne
    @JoinColumn(name = "infringing_song_id")
    private Song infringSong;

    @Column(name = "infringing_url", length = 512, nullable = false)
    private String infringingUrl;

    @Column(nullable = false)
    private String status; // submitted, reviewing, upheld, rejected, counter_pending, resolved

    @ManyToOne
    @JoinColumn(name = "assigned_to")
    private User assignedTo;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @ManyToOne
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "submitted";
    }
}