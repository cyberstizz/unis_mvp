package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ad_views")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdView {
    @Id
    @GeneratedValue
    @Column(columnDefinition = "UUID")
    private UUID adViewId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artist_id")
    private User artist;

    @Column(name = "ad_id")
    private UUID adId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supported_artist_id")
    private User supportedArtist;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referred_artist_id")
    private User referredArtist;

    @Column(name = "revenue_share")
    private BigDecimal revenueShare = BigDecimal.ZERO;

    @Column(name = "viewed_at")
    private LocalDateTime viewedAt = LocalDateTime.now();

    @Column(name = "duration_secs")
    private Integer durationSecs;
}