package com.unis.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Local sponsors: Harlem businesses buy a week in Unis's ad slots themselves.
 *
 *   1. A shop owner scans the QR code on a street-team card (or a store
 *      poster), which opens /sponsor — optionally with ?ref=<code> so the
 *      crew member who brought them gets the credit.
 *   2. They pick Uptown, Downtown or all of Harlem and a week (up to four),
 *      upload a logo, write one line and a link, and pay by card through
 *      Stripe Checkout on Unis's own account.
 *   3. An admin approves it (Admin → Sponsors) or rejects it, which refunds
 *      the full amount automatically.
 *   4. While its weeks run, it shows in the ad slots of people in that area,
 *      labeled "Sponsored · Local". Unis counts how often it was shown and
 *      tapped, and emails the owner those numbers the day after it ends.
 *
 * The money is Unis's revenue: it joins the Community Share pot as "other
 * income" for the month it was paid (suggested automatically at the close,
 * after Stripe's card fee). Nobody is paid for seeing a sponsor.
 *
 * Prices (Railway variables, optional): SPONSOR_WEEK_PRICE_CENTS (one
 * neighborhood, default 2500 = $25) and SPONSOR_HARLEM_WEEK_PRICE_CENTS (all of
 * Harlem, default 4000 = $40). At most {@link #MAX_PER_AREA_WEEK} sponsors share
 * an area in a given week, so each one is actually seen.
 */
@Service
public class SponsorService {

    private static final Logger log = LoggerFactory.getLogger(SponsorService.class);

    public static final int MAX_PER_AREA_WEEK = 3;
    public static final int BOOKABLE_WEEKS = 8;
    public static final int MAX_WEEKS = 4;
    private static final long LOGO_MAX_BYTES = 2L * 1024 * 1024;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    /** Area key → (label, jurisdiction name). */
    private static final Map<String, String[]> AREAS = new LinkedHashMap<>();
    static {
        AREAS.put("uptown", new String[] { "Uptown Harlem", "Uptown Harlem" });
        AREAS.put("downtown", new String[] { "Downtown Harlem", "Downtown Harlem" });
        AREAS.put("harlem", new String[] { "All of Harlem", "Harlem" });
    }

    private final JdbcTemplate jdbc;
    private final StripeApi stripe;
    private final FileStorageService files;
    private final EmailService email;

    @Value("${SPONSOR_WEEK_PRICE_CENTS:2500}")
    private int neighborhoodWeekCents;

    @Value("${SPONSOR_HARLEM_WEEK_PRICE_CENTS:4000}")
    private int harlemWeekCents;

    @Value("${app.frontend.url:https://unisprototypetwo.netlify.app}")
    private String frontendUrl;

    private volatile Map<String, UUID> areaJurisdictions;

    public SponsorService(JdbcTemplate jdbc, StripeApi stripe, FileStorageService files, EmailService email) {
        this.jdbc = jdbc;
        this.stripe = stripe;
        this.files = files;
        this.email = email;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // The sponsor page
    // ═════════════════════════════════════════════════════════════════════════

    /** Areas, weekly prices and the next weeks with how many spots are left. */
    public Map<String, Object> options() {
        List<Map<String, Object>> areas = new ArrayList<>();
        for (Map.Entry<String, String[]> a : AREAS.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", a.getKey());
            m.put("label", a.getValue()[0]);
            m.put("weekPriceCents", weekPrice(a.getKey()));
            areas.add(m);
        }
        List<Map<String, Object>> weeks = new ArrayList<>();
        for (LocalDate monday : bookableWeeks()) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("weekStart", monday.toString());
            Map<String, Integer> left = new LinkedHashMap<>();
            for (String area : AREAS.keySet()) left.put(area, spotsLeft(area, monday));
            w.put("spotsLeft", left);
            weeks.add(w);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("areas", areas);
        m.put("weeks", weeks);
        m.put("maxWeeks", MAX_WEEKS);
        return m;
    }

    /** Validates the form, saves the campaign and starts Stripe Checkout. Returns the checkout URL. */
    public String startCheckout(String businessName, String contactEmail, String headline, String link,
                                String area, String weekStart, int weeks, String referralCode, MultipartFile logo) {
        String name = clean(businessName, 2, 60, "business name");
        String mail = clean(contactEmail, 5, 120, "email");
        if (!EMAIL.matcher(mail).matches()) throw new IllegalArgumentException("Enter a valid email address.");
        String line = clean(headline, 3, 80, "line of text");
        String url = cleanLink(link);
        if (area == null || !AREAS.containsKey(area)) throw new IllegalArgumentException("Pick Uptown, Downtown or all of Harlem.");
        if (weeks < 1 || weeks > MAX_WEEKS) throw new IllegalArgumentException("Pick 1 to " + MAX_WEEKS + " weeks.");
        LocalDate start;
        try {
            start = LocalDate.parse(weekStart);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("Pick a week.");
        }
        if (!bookableWeeks().contains(start)) throw new IllegalArgumentException("That week can't be booked. Pick one of the weeks shown.");
        for (int i = 0; i < weeks; i++) {
            LocalDate week = start.plusWeeks(i);
            if (spotsLeft(area, week) <= 0) {
                throw new IllegalArgumentException("The week of " + pretty(week) + " is full for "
                        + AREAS.get(area)[0] + ". Pick another week or area.");
            }
        }
        String ref = referralCode == null || referralCode.isBlank() ? null
                : referralCode.trim().replaceAll("[^A-Za-z0-9_-]", "");
        if (ref != null && ref.length() > 40) ref = ref.substring(0, 40);

        String logoUrl = null;
        if (logo != null && !logo.isEmpty()) {
            UploadValidator.require(logo, UploadValidator.Kind.IMAGE, LOGO_MAX_BYTES);
            logoUrl = files.storeFile(logo);
        }

        int amount = weekPrice(area) * weeks;
        UUID campaignId = jdbc.queryForObject("""
                INSERT INTO sponsor_campaigns
                    (business_name, contact_email, headline, link_url, logo_url, area, week_start, weeks,
                     amount_cents, referral_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING campaign_id
                """, UUID.class, name, mail, line, url, logoUrl, area, Date.valueOf(start), weeks, amount, ref);

        Map<String, String> f = StripeApi.form();
        f.put("mode", "payment");
        f.put("customer_email", mail);
        f.put("line_items[0][quantity]", "1");
        f.put("line_items[0][price_data][currency]", "usd");
        f.put("line_items[0][price_data][unit_amount]", String.valueOf(amount));
        f.put("line_items[0][price_data][product_data][name]", "Unis local sponsor · " + AREAS.get(area)[0]);
        f.put("line_items[0][price_data][product_data][description]",
                "Week of " + pretty(start) + (weeks > 1 ? " · " + weeks + " weeks" : " · 1 week"));
        f.put("metadata[unis_kind]", "sponsor");
        f.put("metadata[campaign_id]", campaignId.toString());
        f.put("payment_intent_data[metadata][unis_kind]", "sponsor");
        f.put("payment_intent_data[metadata][campaign_id]", campaignId.toString());
        f.put("success_url", frontendUrl + "/sponsor?status=paid");
        f.put("cancel_url", frontendUrl + "/sponsor?status=canceled");
        JsonNode session = stripe.post("checkout/sessions", f, "unis-sponsor-" + campaignId);
        jdbc.update("UPDATE sponsor_campaigns SET stripe_session_id = ?, updated_at = NOW() WHERE campaign_id = ?",
                session.path("id").asText(), campaignId);
        log.info("[Sponsor] action=checkout campaign={} area={} week={} weeks={} amount={} ref={}",
                campaignId, area, start, weeks, amount, ref);
        return session.path("url").asText();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Webhooks
    // ═════════════════════════════════════════════════════════════════════════

    /** checkout.session.completed for a sponsor checkout: paid, waiting for review. */
    public void onCheckoutCompleted(JsonNode session) {
        String campaignId = session.path("metadata").path("campaign_id").asText(null);
        if (campaignId == null || !"paid".equals(session.path("payment_status").asText())) return;
        String paymentIntent = PlusService.text(session, "payment_intent");
        int n = jdbc.update("""
                UPDATE sponsor_campaigns
                   SET status = 'pending_review', stripe_payment_intent_id = ?, paid_at = ?, updated_at = NOW()
                 WHERE campaign_id = ? AND status IN ('pending_payment', 'expired')
                """, paymentIntent, Timestamp.valueOf(LocalDateTime.now()), UUID.fromString(campaignId));
        if (n == 0) return;
        log.info("[Sponsor] action=paid campaign={}", campaignId);
        Map<String, Object> c = campaign(UUID.fromString(campaignId));
        if (c != null) {
            email.sendTransactional((String) c.get("contact_email"), "We got your Unis sponsorship",
                    "<p>Thanks, " + esc((String) c.get("business_name")) + ". Your sponsorship for the week of "
                            + pretty(((Date) c.get("week_start")).toLocalDate()) + " is paid and waiting for a quick review."
                            + " We'll email you when it's approved. If it isn't, you'll get a full refund.</p>");
        }
    }

    /** charge.refunded (e.g. a refund made in the Stripe Dashboard). */
    public void onRefunded(String paymentIntentId) {
        if (paymentIntentId == null) return;
        int n = jdbc.update("""
                UPDATE sponsor_campaigns SET status = 'refunded', updated_at = NOW()
                WHERE stripe_payment_intent_id = ? AND status <> 'refunded'
                """, paymentIntentId);
        if (n > 0) log.info("[Sponsor] action=refunded payment={}", paymentIntentId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Showing sponsors
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * One live sponsor for this viewer, chosen at random, or null. Uptown
     * viewers see Uptown and all-Harlem sponsors; Downtown viewers see Downtown
     * and all-Harlem; everyone else sees any Harlem sponsor.
     */
    public Map<String, Object> serve(UUID viewerJurisdictionId) {
        Map<String, UUID> j = areaJurisdictions();
        List<String> areas;
        if (viewerJurisdictionId != null && viewerJurisdictionId.equals(j.get("uptown"))) areas = List.of("uptown", "harlem");
        else if (viewerJurisdictionId != null && viewerJurisdictionId.equals(j.get("downtown"))) areas = List.of("downtown", "harlem");
        else areas = List.of("uptown", "downtown", "harlem");

        LocalDate today = LocalDate.now(CommunityShareService.UNIS_ZONE);
        List<Object> args = new ArrayList<>(areas);
        args.add(Date.valueOf(today));
        args.add(Date.valueOf(today));
        List<Map<String, Object>> live = jdbc.queryForList(
                "SELECT campaign_id, business_name, headline, logo_url FROM sponsor_campaigns"
                + " WHERE status = 'approved' AND area IN (" + placeholders(areas.size()) + ")"
                + " AND week_start <= ? AND week_start + (weeks * 7) > ?", args.toArray());
        if (live.isEmpty()) return null;
        Map<String, Object> pick = live.get(ThreadLocalRandom.current().nextInt(live.size()));
        UUID id = (UUID) pick.get("campaign_id");
        jdbc.update("UPDATE sponsor_campaigns SET impressions = impressions + 1 WHERE campaign_id = ?", id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("campaignId", id);
        m.put("businessName", pick.get("business_name"));
        m.put("headline", pick.get("headline"));
        m.put("logoUrl", pick.get("logo_url"));
        return m;
    }

    /** Counts a tap and returns where it goes, or null if the sponsor isn't live. */
    public String click(UUID campaignId) {
        List<String> links = jdbc.queryForList(
                "SELECT link_url FROM sponsor_campaigns WHERE campaign_id = ? AND status = 'approved'",
                String.class, campaignId);
        if (links.isEmpty()) return null;
        jdbc.update("UPDATE sponsor_campaigns SET clicks = clicks + 1 WHERE campaign_id = ?", campaignId);
        return links.get(0);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Admin
    // ═════════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> list(int limit) {
        return jdbc.query("""
                SELECT campaign_id, business_name, contact_email, headline, link_url, logo_url, area, week_start,
                       weeks, amount_cents, status, referral_code, paid_at, review_note, reviewed_at,
                       impressions, clicks, created_at
                FROM sponsor_campaigns
                WHERE status <> 'pending_payment' AND status <> 'expired'
                ORDER BY CASE WHEN status = 'pending_review' THEN 0 ELSE 1 END, week_start DESC, created_at DESC
                LIMIT ?
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("campaignId", rs.getObject("campaign_id", UUID.class));
            m.put("businessName", rs.getString("business_name"));
            m.put("contactEmail", rs.getString("contact_email"));
            m.put("headline", rs.getString("headline"));
            m.put("linkUrl", rs.getString("link_url"));
            m.put("logoUrl", rs.getString("logo_url"));
            m.put("area", rs.getString("area"));
            m.put("areaLabel", AREAS.get(rs.getString("area"))[0]);
            m.put("weekStart", rs.getDate("week_start").toLocalDate().toString());
            m.put("weeks", rs.getInt("weeks"));
            m.put("amountCents", rs.getInt("amount_cents"));
            m.put("status", rs.getString("status"));
            m.put("referralCode", rs.getString("referral_code"));
            Timestamp paid = rs.getTimestamp("paid_at");
            m.put("paidAt", paid == null ? null : paid.toLocalDateTime().toString());
            m.put("reviewNote", rs.getString("review_note"));
            m.put("impressions", rs.getInt("impressions"));
            m.put("clicks", rs.getInt("clicks"));
            return m;
        }, Math.max(1, Math.min(limit, 500)));
    }

    public void approve(UUID campaignId, UUID adminId) {
        int n = jdbc.update("""
                UPDATE sponsor_campaigns
                   SET status = 'approved', reviewed_by = ?, reviewed_at = ?, updated_at = NOW()
                 WHERE campaign_id = ? AND status = 'pending_review'
                """, adminId, Timestamp.valueOf(LocalDateTime.now()), campaignId);
        if (n == 0) throw new IllegalStateException("Only a paid sponsorship waiting for review can be approved.");
        log.info("[Sponsor] action=approve campaign={} by={}", campaignId, adminId);
        Map<String, Object> c = campaign(campaignId);
        if (c != null) {
            email.sendTransactional((String) c.get("contact_email"), "Your Unis sponsorship is approved",
                    "<p>Good news, " + esc((String) c.get("business_name")) + ". Your sponsorship runs from "
                            + pretty(((Date) c.get("week_start")).toLocalDate()) + " for "
                            + c.get("weeks") + (((Integer) c.get("weeks")) == 1 ? " week" : " weeks")
                            + " in " + AREAS.get((String) c.get("area"))[0] + ".</p>");
        }
    }

    /** Rejects a paid sponsorship and refunds all of it. Also works on an approved one that hasn't started. */
    public void reject(UUID campaignId, String note, UUID adminId) {
        Map<String, Object> c = campaign(campaignId);
        if (c == null) throw new IllegalArgumentException("No such sponsorship.");
        String status = (String) c.get("status");
        LocalDate start = ((Date) c.get("week_start")).toLocalDate();
        boolean started = !LocalDate.now(CommunityShareService.UNIS_ZONE).isBefore(start);
        if (!"pending_review".equals(status) && !("approved".equals(status) && !started)) {
            throw new IllegalStateException("Only a sponsorship waiting for review, or approved and not started yet, can be rejected.");
        }
        String paymentIntent = (String) c.get("stripe_payment_intent_id");
        if (paymentIntent == null) throw new IllegalStateException("This sponsorship has no payment to refund.");
        if (note == null || note.isBlank()) throw new IllegalArgumentException("Write a short reason; the sponsor sees it.");

        Map<String, String> f = StripeApi.form();
        f.put("payment_intent", paymentIntent);
        f.put("metadata[campaign_id]", campaignId.toString());
        stripe.post("refunds", f, "unis-sponsor-refund-" + campaignId);

        jdbc.update("""
                UPDATE sponsor_campaigns
                   SET status = 'refunded', review_note = ?, reviewed_by = ?, reviewed_at = ?, updated_at = NOW()
                 WHERE campaign_id = ?
                """, note.trim(), adminId, Timestamp.valueOf(LocalDateTime.now()), campaignId);
        log.info("[Sponsor] action=reject campaign={} by={}", campaignId, adminId);
        email.sendTransactional((String) c.get("contact_email"), "About your Unis sponsorship",
                "<p>Hi " + esc((String) c.get("business_name")) + ", we couldn't run this sponsorship: "
                        + esc(note.trim()) + "</p><p>We've refunded the full amount to your card. It can take 5 to 10 "
                        + "business days to appear. You're welcome to book again.</p>");
    }

    /** For the Community Share close: sponsor money paid in the month, after Stripe's card fee. */
    public BigDecimal suggestedIncome(YearMonth month) {
        LocalDateTime[] range = CommunityShareService.serverRange(month);
        Long cents = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_cents - ((amount_cents * ? + 999) / 1000 + ?)), 0)
                FROM sponsor_campaigns
                WHERE status IN ('pending_review', 'approved') AND paid_at >= ? AND paid_at < ?
                """, Long.class, PlatformFees.STRIPE_PERCENT_PER_THOUSAND, PlatformFees.STRIPE_FIXED_CENTS,
                Timestamp.valueOf(range[0]), Timestamp.valueOf(range[1]));
        return BigDecimal.valueOf(Math.max(0, cents == null ? 0 : cents), 2);
    }

    /** Every morning: email each sponsor whose weeks have ended how many times it was shown and tapped. */
    @Scheduled(cron = "0 7 10 * * *", zone = "America/New_York")
    public void sendEndReports() {
        LocalDate today = LocalDate.now(CommunityShareService.UNIS_ZONE);
        List<Map<String, Object>> ended = jdbc.queryForList("""
                SELECT campaign_id, business_name, contact_email, area, week_start, weeks, impressions, clicks
                FROM sponsor_campaigns
                WHERE status = 'approved' AND report_sent_at IS NULL AND week_start + (weeks * 7) <= ?
                """, Date.valueOf(today));
        for (Map<String, Object> c : ended) {
            int shown = ((Number) c.get("impressions")).intValue();
            int taps = ((Number) c.get("clicks")).intValue();
            boolean sent = email.sendTransactional((String) c.get("contact_email"), "How your Unis sponsorship did",
                    "<p>Hi " + esc((String) c.get("business_name")) + ", your sponsorship in "
                            + AREAS.get((String) c.get("area"))[0] + " from "
                            + pretty(((Date) c.get("week_start")).toLocalDate()) + " has ended.</p>"
                            + "<p>It was shown <strong>" + shown + "</strong> " + (shown == 1 ? "time" : "times")
                            + " and tapped <strong>" + taps + "</strong> " + (taps == 1 ? "time" : "times") + ".</p>"
                            + "<p>Thanks for backing Harlem's artists. Book again any time at "
                            + esc(frontendUrl) + "/sponsor.</p>");
            if (sent) {
                jdbc.update("UPDATE sponsor_campaigns SET report_sent_at = ?, updated_at = NOW() WHERE campaign_id = ?",
                        Timestamp.valueOf(LocalDateTime.now()), c.get("campaign_id"));
            }
        }
        if (!ended.isEmpty()) log.info("[Sponsor] action=end_reports count={}", ended.size());
    }

    /** Every hour: give up on checkouts nobody finished after a day, and remove their logos. */
    @Scheduled(cron = "0 23 * * * *", zone = "America/New_York")
    public void expireUnpaid() {
        List<Map<String, Object>> stale = jdbc.queryForList("""
                SELECT campaign_id, logo_url FROM sponsor_campaigns
                WHERE status = 'pending_payment' AND created_at < ?
                """, Timestamp.valueOf(LocalDateTime.now().minusHours(25)));
        for (Map<String, Object> s : stale) {
            jdbc.update("UPDATE sponsor_campaigns SET status = 'expired', updated_at = NOW() WHERE campaign_id = ?",
                    s.get("campaign_id"));
            String logo = (String) s.get("logo_url");
            if (logo != null) {
                try {
                    files.deleteFile(logo);
                } catch (Exception e) {
                    log.warn("[Sponsor] action=expire_logo campaign={} err={}", s.get("campaign_id"), e.getMessage());
                }
            }
        }
        if (!stale.isEmpty()) log.info("[Sponsor] action=expire_unpaid count={}", stale.size());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String placeholders(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    private int weekPrice(String area) {
        return "harlem".equals(area) ? harlemWeekCents : neighborhoodWeekCents;
    }

    /** The next {@link #BOOKABLE_WEEKS} Mondays, starting next Monday (a day for review). */
    private static List<LocalDate> bookableWeeks() {
        LocalDate today = LocalDate.now(CommunityShareService.UNIS_ZONE);
        LocalDate first = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        List<LocalDate> weeks = new ArrayList<>();
        for (int i = 0; i < BOOKABLE_WEEKS; i++) weeks.add(first.plusWeeks(i));
        return weeks;
    }

    /**
     * Spots left in an area for a week. An all-Harlem sponsor uses a spot in
     * both neighborhoods. Unpaid checkouts hold their spot for 30 minutes.
     */
    private int spotsLeft(String area, LocalDate monday) {
        List<String> clashing = "harlem".equals(area)
                ? List.of("uptown", "downtown", "harlem")
                : List.of(area, "harlem");
        String sql = "SELECT area, COUNT(*) AS n FROM sponsor_campaigns"
                + " WHERE area IN (" + placeholders(clashing.size()) + ")"
                + " AND week_start <= ? AND week_start + (weeks * 7) > ?"
                + " AND (status IN ('pending_review', 'approved')"
                + "      OR (status = 'pending_payment' AND created_at > ?))"
                + " GROUP BY area";
        List<Object> args = new ArrayList<>(clashing);
        args.add(Date.valueOf(monday));
        args.add(Date.valueOf(monday));
        args.add(Timestamp.valueOf(LocalDateTime.now().minusMinutes(30)));
        Map<String, Integer> byArea = new HashMap<>();
        jdbc.query(sql, rs -> { byArea.put(rs.getString("area"), rs.getInt("n")); }, args.toArray());
        int left;
        if ("harlem".equals(area)) {
            int up = byArea.getOrDefault("uptown", 0), down = byArea.getOrDefault("downtown", 0);
            int all = byArea.getOrDefault("harlem", 0);
            left = MAX_PER_AREA_WEEK - all - Math.max(up, down);
        } else {
            left = MAX_PER_AREA_WEEK - byArea.getOrDefault(area, 0) - byArea.getOrDefault("harlem", 0);
        }
        return Math.max(0, left);
    }

    private Map<String, UUID> areaJurisdictions() {
        if (areaJurisdictions != null) return areaJurisdictions;
        Map<String, UUID> m = new HashMap<>();
        for (Map.Entry<String, String[]> a : AREAS.entrySet()) {
            List<UUID> ids = jdbc.queryForList("SELECT jurisdiction_id FROM jurisdictions WHERE name = ?",
                    UUID.class, a.getValue()[1]);
            if (!ids.isEmpty()) m.put(a.getKey(), ids.get(0));
        }
        areaJurisdictions = m;
        return m;
    }

    private Map<String, Object> campaign(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sponsor_campaigns WHERE campaign_id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String clean(String value, int min, int max, String what) {
        String v = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (v.length() < min) throw new IllegalArgumentException("Enter your " + what + ".");
        if (v.length() > max) throw new IllegalArgumentException("Keep your " + what + " under " + (max + 1) + " characters.");
        return v;
    }

    /** Accepts "shop.com" or "https://shop.com/…"; only http(s) links to a real host. */
    private static String cleanLink(String link) {
        String v = link == null ? "" : link.trim();
        if (v.isEmpty()) throw new IllegalArgumentException("Enter a link (your website, Instagram or Google Maps page).");
        if (!v.matches("(?i)^https?://.*")) v = "https://" + v;
        if (v.length() > 300) throw new IllegalArgumentException("That link is too long.");
        try {
            URI u = new URI(v);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            if (!(scheme.equals("https") || scheme.equals("http")) || u.getHost() == null || !u.getHost().contains(".")) {
                throw new IllegalArgumentException("That link doesn't look right.");
            }
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("That link doesn't look right.");
        }
        return v;
    }

    private static String pretty(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US));
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
