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
    @GeneratedValue(generator = "UUID")
    @Column(name = "ad_view_id")
    private UUID adViewId;

    // The user who viewed the ad
    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    // The artist associated with the content (if applicable)
    @ManyToOne
    @JoinColumn(name = "artist_id")
    private User artist;

    // Ad identifier (for future ad network integration)
    @Column(name = "ad_id")
    private UUID adId;

    // The viewer's supported artist (receives 15%)
    @ManyToOne
    @JoinColumn(name = "supported_artist_id")
    private User supportedArtist;

    // Level 1 referrer — the person who directly referred the viewer (receives 10%)
    @ManyToOne
    @JoinColumn(name = "referred_artist_id")
    private User referredArtist;

    // Level 2 referrer — the person who referred the Level 1 referrer (receives 5%)
    @ManyToOne
    @JoinColumn(name = "referrer_level2_id")
    private User referrerLevel2;

    // Level 3 referrer — the person who referred the Level 2 referrer (receives 2%)
    @ManyToOne
    @JoinColumn(name = "referrer_level3_id")
    private User referrerLevel3;

    // Total revenue for this ad view (CPM / 1000)
    @Column(name = "revenue_share", precision = 12, scale = 6)
    private BigDecimal revenueShare;

    // Ad duration in seconds (for future audio/video ads)
    @Column(name = "duration_secs")
    private Integer durationSecs;

    // When the ad was viewed
    @Column(name = "viewed_at")
    private LocalDateTime viewedAt;
}