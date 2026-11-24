package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** One change a team member made on an artist's page, shown to the artist. */
@Entity
@Table(name = "artist_team_activity")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArtistTeamActivity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "artist_id", nullable = false)
    private UUID artistId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false, length = 240)
    private String action;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
