package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "supporters")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Supporter {
    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID supporterId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listener_id")
    private User listener;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artist_id")
    private User artist;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
