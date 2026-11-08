package com.unis.service.referralnotice;

/**
 * Everything one "new in your network" email needs, for one reader.
 *
 * @param level                  where the new member sits in the reader's network: 1, 2 or 3
 * @param recipientName          the reader's username
 * @param newMemberName          the new member's username, or null when their profile is private
 * @param newMemberPhoto         a link a mail app can load, or null
 * @param newMemberPhoneVerified their listening only counts toward the Community Share once true
 * @param recipientPhoneVerified the reader's share can only be paid once true
 * @param referralCode           the reader's own code, for the "keep it going" block (may be null)
 * @param level1Count            people in the reader's network at each level, the new member included
 * @param prelaunch              Unis isn't open yet: no "Open Unis" button, since sign-in is closed
 */
public record ReferralNotice(
        int level,
        String recipientName,
        String newMemberName,
        String newMemberPhoto,
        boolean newMemberPhoneVerified,
        boolean recipientPhoneVerified,
        String referralCode,
        int level1Count,
        int level2Count,
        int level3Count,
        boolean prelaunch,
        String appBaseUrl,
        String unsubscribeUrl,
        String postalAddress) {

    /** The percentage of the new member's monthly Community Share the reader gets. */
    public String percent() {
        return switch (level) {
            case 1 -> "10%";
            case 2 -> "5%";
            default -> "2%";
        };
    }

    public int networkTotal() {
        return level1Count + level2Count + level3Count;
    }
}
