package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "likes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Like {
    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID likeId;

    @Column(name = "media_type", nullable = false)
    private String mediaType;  // 'song' or 'video'

    @Column(name = "media_id", nullable = false)
    private UUID mediaId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}