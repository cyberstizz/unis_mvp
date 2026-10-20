package com.unis.service;

/**
 * Unis's fee on song sales and direct support: the "break-even gate".
 *
 * Both are Stripe destination charges, so Stripe bills its card fee
 * (2.9% + 30¢ on a US card) to Unis, out of Unis's application fee. A flat 10%
 * doesn't cover that on small payments: on a $1.99 song Unis would keep 20¢ and
 * pay Stripe 36¢. So the fee is:
 *
 *     10% of the payment, or Stripe's card fee + 1¢, whichever is higher.
 *
 * Under about $4.35 the gate decides and Unis clears exactly 1¢ on a US card;
 * from about $4.35 up it's a plain 10%. The artist receives the rest; the buyer
 * pays the listed price and nothing on top.
 *
 *   price   Unis fee   artist gets   Unis keeps after Stripe
 *   $0.94     34¢        $0.60          1¢   (the minimum song price)
 *   $1.00     34¢        $0.66          1¢   (the minimum support)
 *   $1.99     37¢        $1.62          1¢
 *   $5.00     50¢        $4.50          5¢
 *   $10.00   $1.00       $9.00         41¢
 *
 * Known limits: an international card costs 1.5% more, so on a small payment
 * from one Unis can come out a cent or two short; and Stripe keeps its card
 * fee when a payment is refunded.
 *
 * The web app mirrors this in src/utils/platformFee.js (to show artists what
 * they'll receive); change both together. Stripe's card fee is also used by
 * CommunityShareService.suggestedFeeIncome so only Unis's real margin is
 * shared.
 */
public final class PlatformFees {

    private PlatformFees() {}

    /**
     * Lowest price a song can be sold for ($0.94): 5¢ under the iTunes Store's
     * 99¢ tier, so a song can always be cheapest on Unis. Used by both the
     * upload and the download-settings paths.
     */
    public static final int MIN_SONG_PRICE_CENTS = 94;

    /** Unis's normal cut of a song sale or support payment. */
    public static final double DEFAULT_PERCENT = 0.10;

    /** Stripe's US card price: 2.9% (as 29 per 1,000) + 30¢. */
    public static final int STRIPE_PERCENT_PER_THOUSAND = 29;
    public static final int STRIPE_FIXED_CENTS = 30;

    /** What Unis clears above Stripe's card fee on a small payment. */
    public static final int MIN_MARGIN_CENTS = 1;

    /** Stripe's card fee on a payment, rounded up to the cent. */
    public static int stripeCardFeeCents(int amountCents) {
        if (amountCents <= 0) return 0;
        // ceil(amount × 2.9%) in whole numbers, so no floating-point drift.
        return (amountCents * STRIPE_PERCENT_PER_THOUSAND + 999) / 1000 + STRIPE_FIXED_CENTS;
    }

    /** The smallest fee that still covers Stripe's card fee plus Unis's 1¢. */
    public static int breakEvenFeeCents(int amountCents) {
        return stripeCardFeeCents(amountCents) + MIN_MARGIN_CENTS;
    }

    /** Unis's fee with the default 10%. */
    public static int feeCents(int amountCents) {
        return feeCents(amountCents, DEFAULT_PERCENT);
    }

    /** Unis's fee: {@code percent} of the payment, or the break-even fee if that's higher. */
    public static int feeCents(int amountCents, double percent) {
        if (amountCents <= 0) return 0;
        int share = (int) Math.round(amountCents * percent);
        int fee = Math.max(share, breakEvenFeeCents(amountCents));
        return Math.min(fee, amountCents);
    }

    /** What Unis keeps from its fee after Stripe's card fee (can't go below zero). */
    public static int marginCents(int amountCents, int feeCents) {
        return Math.max(0, feeCents - stripeCardFeeCents(amountCents));
    }
}
