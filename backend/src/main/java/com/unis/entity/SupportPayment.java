package com.unis.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A one-time direct support payment ("tip") from a listener to an artist.
 *
 * Structurally this is a {@link Purchase} without a song: the same
 * destination-charge flow ({@code transfer_data.destination}) sends the money
 * straight to the artist's connected Stripe account, Unis takes its
 * application fee, and the row is recorded here for the artist's dashboard.
 *
 * The {@code note} is the message that rides along with the support, and
 * {@code source} records where it was sent from ("profile" or "dm") so the
 * in-thread support flow (Phase B) can attribute correctly.
 */
@Entity
@Table(name = "support_payments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supporter_id", nullable = false)
    private UUID supporterId;

    @Column(name = "artist_id", nullable = false)
    private UUID artistId;

    @Column(nullable = false)
    private Integer amount;            // total paid, in cents

    @Column(name = "platform_fee", nullable = false)
    private Integer platformFee;       // Unis application fee, in cents

    @Column(length = 280)
    private String note;               // optional message attached to the support

    @Column(nullable = false)
    @Builder.Default
    private String source = "profile"; // "profile" | "dm"

    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    @Column(nullable = false)
    @Builder.Default
    private String status = "completed"; // completed | refunded | disputed

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}