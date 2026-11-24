package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Someone who helps run an artist's page with their own Unis account.
 * MANAGER: upload and edit music, releases, profile and photos; see stats and
 * earnings; message supporters. VIEWER: see only. Only the artist invites,
 * changes roles, removes, and handles money. See TeamActAsFilter.
 */
@Entity
@Table(name = "artist_team_members",
       uniqueConstraints = @UniqueConstraint(columnNames = {"artist_id", "member_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArtistTeamMember {

    public enum Role { MANAGER, VIEWER }
    public enum Status { PENDING, ACCEPTED }

    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "artist_id", nullable = false)
    private UUID artistId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;
}
