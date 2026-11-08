package com.unis.service.referralnotice;

import com.unis.service.digest.WeeklyDigestService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The data side of the "new in your network" email (ReferralNoticeScheduler
 * sends it, ReferralNoticeRenderer draws it).
 *
 * <p><b>When a signup is announced</b>: once the new member confirms their
 * email (they can't sign in before that, so it filters out abandoned and fake
 * signups), within 3 days of signing up. Test and staff accounts
 * (exclude_from_metrics) are never announced: their listening doesn't count.
 *
 * <p><b>Who hears about it</b>: the same chain the monthly Community Share
 * pays (CommunityShareService.loadChains): level 1 is the code's owner,
 * level 2 whoever invited them, level 3 one step further. Each must have
 * email notifications on, a confirmed email, a live account, and not be a
 * staff or test account (those can't be paid, so the email would be untrue).
 *
 * <p><b>Once only</b>: a signup is claimed with a conditional UPDATE before
 * anything is sent, so two runs or two servers can't both announce it.
 * Tables: backend/sql/V2026_11a__referral_notices.sql
 */
@Service
public class ReferralNoticeService {

    /** How long after signing up a member can still be announced. */
    static final String LOOKBACK = "3 days";

    public record Pending(UUID referralId, UUID memberId, String username, String photoUrl,
                          boolean publicProfile, boolean phoneVerified) {}

    public record Upline(int level, UUID userId, String username, String email,
                         String unsubscribeToken, String referralCode, boolean phoneVerified) {}

    private final JdbcTemplate jdbc;
    private final WeeklyDigestService digestService;
    private final String appBaseUrl;
    private final String apiBaseUrl;
    private final String postalAddress;
    private final boolean prelaunch;

    public ReferralNoticeService(JdbcTemplate jdbc,
                                 WeeklyDigestService digestService,
                                 @Value("${unis.app-base-url:http://localhost:5173}") String appBaseUrl,
                                 @Value("${unis.api-base-url:http://localhost:8080}") String apiBaseUrl,
                                 @Value("${unis.mail.postal-address:}") String postalAddress,
                                 @Value("${unis.prelaunch:false}") boolean prelaunch) {
        this.jdbc = jdbc;
        this.digestService = digestService;
        this.appBaseUrl = trimSlash(appBaseUrl);
        this.apiBaseUrl = trimSlash(apiBaseUrl);
        this.postalAddress = postalAddress;
        this.prelaunch = prelaunch;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // The queue
    // ═════════════════════════════════════════════════════════════════════════

    /** Signups waiting to be announced, oldest first. */
    public List<Pending> pending(int limit) {
        return jdbc.query("""
                SELECT r.referral_id, u.user_id, u.username, u.photo_url, u.public_profile, u.phone_verified
                FROM referrals r
                JOIN users u ON u.user_id = r.referred_id
                WHERE r.notice_sent_at IS NULL
                  AND r.created_at > NOW() - CAST(? AS interval)
                  AND u.email_verified = TRUE
                  AND u.deleted_at IS NULL
                  AND u.exclude_from_metrics = FALSE
                ORDER BY r.created_at
                LIMIT ?
                """,
            (rs, i) -> new Pending(
                rs.getObject("referral_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("username"),
                rs.getString("photo_url"),
                rs.getBoolean("public_profile"),
                rs.getBoolean("phone_verified")),
            LOOKBACK, limit);
    }

    /** The people credited for this member, level 1 first, who can get the email. */
    public List<Upline> upline(UUID memberId) {
        List<Upline> rows = jdbc.query("""
                WITH up AS (
                    SELECT 1 AS lvl, r1.referrer_id AS uid
                    FROM referrals r1 WHERE r1.referred_id = ?
                    UNION ALL
                    SELECT 2, r2.referrer_id
                    FROM referrals r1
                    JOIN referrals r2 ON r2.referred_id = r1.referrer_id
                    WHERE r1.referred_id = ?
                    UNION ALL
                    SELECT 3, r3.referrer_id
                    FROM referrals r1
                    JOIN referrals r2 ON r2.referred_id = r1.referrer_id
                    JOIN referrals r3 ON r3.referred_id = r2.referrer_id
                    WHERE r1.referred_id = ?
                )
                SELECT up.lvl, u.user_id, u.username, u.email, CAST(u.unsubscribe_token AS text) AS token,
                       u.referral_code, u.phone_verified
                FROM up
                JOIN users u ON u.user_id = up.uid
                WHERE u.user_id <> ?
                  AND u.email_notifications = TRUE
                  AND u.email_verified = TRUE
                  AND u.deleted_at IS NULL
                  AND u.exclude_from_metrics = FALSE
                  AND u.email IS NOT NULL AND u.email <> ''
                ORDER BY up.lvl
                """,
            (rs, i) -> new Upline(
                rs.getInt("lvl"),
                rs.getObject("user_id", UUID.class),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("token"),
                rs.getString("referral_code"),
                rs.getBoolean("phone_verified")),
            memberId, memberId, memberId, memberId);

        // One email per person, at their closest level (a chain can't loop,
        // but a duplicate referral row shouldn't double-send).
        Set<UUID> seen = new HashSet<>();
        List<Upline> out = new ArrayList<>(rows.size());
        for (Upline u : rows) {
            if (seen.add(u.userId())) out.add(u);
        }
        return out;
    }

    /** Marks a signup as announced. False when another run already has it. */
    public boolean claim(UUID referralId) {
        return jdbc.update(
            "UPDATE referrals SET notice_sent_at = NOW() WHERE referral_id = ? AND notice_sent_at IS NULL",
            referralId) == 1;
    }

    /** Hands a signup back to the queue (every send failed, e.g. Resend was down). */
    public void release(UUID referralId) {
        jdbc.update("UPDATE referrals SET notice_sent_at = NULL, notice_emails = 0 WHERE referral_id = ?", referralId);
    }

    public void recordSent(UUID referralId, int emails) {
        jdbc.update("UPDATE referrals SET notice_emails = ? WHERE referral_id = ?", emails, referralId);
    }

    /** Referral emails sent since midnight UTC. */
    public int sentToday() {
        Integer n = jdbc.queryForObject("""
                SELECT COALESCE(SUM(notice_emails), 0) FROM referrals
                WHERE notice_sent_at >= date_trunc('day', NOW())
                """, Integer.class);
        return n == null ? 0 : n;
    }

    /** Sunday emails sent since midnight UTC (0 on other days). */
    public int digestSentToday() {
        Integer n = jdbc.queryForObject("""
                SELECT COALESCE(SUM(awards_created), 0) FROM cron_executions
                WHERE job_name = 'WEEKLY_DIGEST' AND started_at >= date_trunc('day', NOW())
                """, Integer.class);
        return n == null ? 0 : n;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // The email
    // ═════════════════════════════════════════════════════════════════════════

    public ReferralNotice build(Upline reader, Pending member) {
        int[] net = network(reader.userId());
        return new ReferralNotice(
            reader.level(),
            reader.username(),
            member.publicProfile() ? member.username() : null,
            member.publicProfile() ? digestService.absoluteMedia(member.photoUrl()) : null,
            member.phoneVerified(),
            reader.phoneVerified(),
            reader.referralCode(),
            net[0], net[1], net[2],
            prelaunch,
            appBaseUrl,
            unsubscribeUrl(reader.unsubscribeToken()),
            postalAddress);
    }

    /**
     * For the admin preview and test send: the email the admin would get if
     * someone joined at this level of their network. The new member is their
     * most recent level-1 referral when they have one, otherwise a stand-in.
     */
    public Optional<ReferralNotice> sample(UUID adminId, int level) {
        List<Upline> me = jdbc.query("""
                SELECT u.user_id, u.username, u.email, CAST(u.unsubscribe_token AS text) AS token,
                       u.referral_code, u.phone_verified
                FROM users u WHERE u.user_id = ? AND u.deleted_at IS NULL
                """,
            (rs, i) -> new Upline(
                Math.max(1, Math.min(3, level)),
                rs.getObject("user_id", UUID.class),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("token"),
                rs.getString("referral_code"),
                rs.getBoolean("phone_verified")),
            adminId);
        if (me.isEmpty()) return Optional.empty();

        List<Pending> recent = jdbc.query("""
                SELECT r.referral_id, u.user_id, u.username, u.photo_url, u.public_profile, u.phone_verified
                FROM referrals r JOIN users u ON u.user_id = r.referred_id
                WHERE r.referrer_id = ? AND u.deleted_at IS NULL
                ORDER BY r.created_at DESC LIMIT 1
                """,
            (rs, i) -> new Pending(
                rs.getObject("referral_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("username"),
                rs.getString("photo_url"),
                rs.getBoolean("public_profile"),
                rs.getBoolean("phone_verified")),
            adminId);
        Pending member = recent.isEmpty()
            ? new Pending(null, null, "newmember", null, true, false)
            : recent.get(0);
        return Optional.of(build(me.get(0), member));
    }

    /** An account's email, for the admin test send. */
    public Optional<String> emailOf(UUID userId) {
        return jdbc.queryForList(
                "SELECT email FROM users WHERE user_id = ? AND deleted_at IS NULL AND email IS NOT NULL AND email <> ''",
                String.class, userId)
            .stream().findFirst();
    }

    public String unsubscribeUrl(String token) {
        return apiBaseUrl + "/api/v1/users/unsubscribe?token=" + token;
    }

    // ═════════════════════════════════════════════════════════════════════════

    /** People in this member's network at levels 1, 2 and 3 (live accounts). */
    private int[] network(UUID userId) {
        int[] out = new int[3];
        jdbc.query("""
                WITH net AS (
                    SELECT r1.referred_id AS uid, 1 AS lvl FROM referrals r1 WHERE r1.referrer_id = ?
                    UNION ALL
                    SELECT r2.referred_id, 2 FROM referrals r1
                      JOIN referrals r2 ON r2.referrer_id = r1.referred_id WHERE r1.referrer_id = ?
                    UNION ALL
                    SELECT r3.referred_id, 3 FROM referrals r1
                      JOIN referrals r2 ON r2.referrer_id = r1.referred_id
                      JOIN referrals r3 ON r3.referrer_id = r2.referred_id WHERE r1.referrer_id = ?
                )
                SELECT n.lvl, COUNT(DISTINCT n.uid) AS people
                FROM net n JOIN users u ON u.user_id = n.uid AND u.deleted_at IS NULL
                GROUP BY n.lvl
                """, rs -> {
            int lvl = rs.getInt("lvl");
            if (lvl >= 1 && lvl <= 3) out[lvl - 1] = rs.getInt("people");
        }, userId, userId, userId);
        return out;
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.replaceAll("/+$", "");
    }
}
