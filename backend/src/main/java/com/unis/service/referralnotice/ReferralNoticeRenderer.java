package com.unis.service.referralnotice;

import com.unis.service.mail.EmailBrand;

import java.text.NumberFormat;
import java.util.Locale;

import static com.unis.service.mail.EmailBrand.*;

/**
 * Draws the "new in your network" email: someone joined Unis with the
 * reader's code (level 1), or further down their network (levels 2 and 3).
 *
 * <p>Same look and the same email rules as the Sunday email
 * (WeeklyDigestRenderer): dark card stack, table layout, inline styles, a
 * 600px column, the Unis logo as a PNG (EmailBrand), a plain-text part and a
 * hidden preheader.
 *
 * <p>The moment it's built around: the new member's photo with a blue ring,
 * then the percentage of their monthly Community Share that is now the
 * reader's, big. Below that, the reader's whole network with the level that
 * just grew lit up, and their code to keep it going.
 *
 * <p>Wording: earnings are "the monthly Community Share", based on listening.
 * Never "ads" or "browsing" (ad network policy), and never a dollar amount.
 */
public final class ReferralNoticeRenderer {

    public record Rendered(String subject, String preheader, String html, String text) {}

    private static final String UTM = "utm_source=unis_referral&utm_medium=email&utm_campaign=network";
    private static final String HERO_TOP = "#14225a"; // the blue glow at the top of the hero card
    private static final String MONO = "'SF Mono',Menlo,Consolas,'Liberation Mono',monospace";

    private ReferralNoticeRenderer() {}

    // ═════════════════════════════════════════════════════════════════════════
    public static Rendered render(ReferralNotice n) {
        String subject = subject(n);
        String preheader = "You now get " + n.percent() + " of " + theirs(n)
            + " monthly Community Share, every month they listen on Unis.";

        StringBuilder h = new StringBuilder(16_000);
        h.append("<!doctype html><html lang=\"en\"><head>")
         .append("<meta charset=\"utf-8\">")
         .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
         .append("<meta name=\"x-apple-disable-message-reformatting\">")
         .append("<meta name=\"color-scheme\" content=\"dark\">")
         .append("<meta name=\"supported-color-schemes\" content=\"dark\">")
         .append("<title>").append(esc(subject)).append("</title>")
         .append("<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700;800&display=swap\" rel=\"stylesheet\">")
         .append("<style>")
         .append("body{margin:0;padding:0;background:").append(BG).append(";}")
         .append("a{text-decoration:none;}")
         .append("@media (max-width:620px){")
         .append(".wrap{width:100%!important;}")
         .append(".px{padding-left:14px!important;padding-right:14px!important;}")
         .append(".h1{font-size:28px!important;line-height:34px!important;}")
         .append(".stack{display:block!important;width:auto!important;text-align:center!important;}")
         .append(".pct{font-size:60px!important;line-height:64px!important;padding:26px 0 6px!important;}")
         .append(".stacktext{padding:0 22px 24px!important;}")
         .append(".tilenum{font-size:26px!important;}")
         .append(".code{font-size:20px!important;letter-spacing:0.08em!important;}")
         .append("}")
         .append("</style></head>")
         .append("<body style=\"margin:0;padding:0;background:").append(BG).append(";\" bgcolor=\"").append(BG).append("\">");

        h.append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;mso-hide:all;\">")
         .append(esc(preheader))
         .append("&#8204;&nbsp;".repeat(60))
         .append("</div>");

        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(BG)
         .append("\" style=\"background:").append(BG).append(";\"><tr><td align=\"center\" style=\"padding:24px 10px 40px;\">")
         .append("<table role=\"presentation\" class=\"wrap\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:600px;max-width:600px;\">");

        EmailBrand.header(h, n.appBaseUrl(), link(n, "/"), "New in your network");
        hero(h, n);
        boost(h, n);
        notes(h, n);
        network(h, n);
        cta(h, n);
        code(h, n);
        footer(h, n);

        h.append("</table></td></tr></table></body></html>");

        return new Rendered(subject, preheader, h.toString(), plainText(n, subject));
    }

    // ── Words ─────────────────────────────────────────────────────────────────

    static String subject(ReferralNotice n) {
        String who = n.newMemberName();
        if (n.level() == 1) {
            return (who != null ? who : "Someone") + " just joined Unis with your code";
        }
        return who != null ? "Your network just grew: " + who + " joined Unis" : "Your network just grew on Unis";
    }

    private static String name(ReferralNotice n) {
        return n.newMemberName() != null ? n.newMemberName() : "someone new";
    }

    private static String nameCap(ReferralNotice n) {
        return n.newMemberName() != null ? n.newMemberName() : "Someone new";
    }

    /** "Jordan’s" or "their". */
    private static String theirs(ReferralNotice n) {
        return n.newMemberName() != null ? n.newMemberName() + "’s" : "their";
    }

    private static String headline(ReferralNotice n) {
        return n.level() == 1
            ? nameCap(n) + " joined with your code"
            : nameCap(n) + " just joined your network";
    }

    private static String relation(ReferralNotice n) {
        return switch (n.level()) {
            case 1 -> "That puts " + name(n) + " at Level 1 in your network: the closest spot, and the biggest share.";
            case 2 -> "Someone you invited brought " + name(n) + " to Unis. That puts them at Level 2 in your network.";
            default -> nameCap(n) + " came in through your network, three invites down from you. That puts them at Level 3.";
        };
    }

    // ── Sections ──────────────────────────────────────────────────────────────

    /** The new member's photo with a blue ring, the level, the headline. */
    private static void hero(StringBuilder h, ReferralNotice n) {
        h.append("<tr><td class=\"px\" style=\"padding:0 24px 16px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(CARD)
         .append("\" style=\"background-color:").append(CARD).append(";background-image:linear-gradient(180deg,").append(HERO_TOP)
         .append(" 0%,").append(CARD).append(" 62%);border:1px solid ").append(LINE).append(";border-radius:24px;\">")
         .append("<tr><td align=\"center\" style=\"padding:38px 26px 34px;\">");

        avatar(h, n);

        // Level pill
        h.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:20px auto 16px;\"><tr>")
         .append("<td bgcolor=\"").append(MONO_BG).append("\" style=\"background-color:").append(MONO_BG)
         .append(";border:1px solid #2a3f8f;border-radius:999px;padding:7px 14px;").append(font(12, 700, ACCENT_INK))
         .append("letter-spacing:0.08em;text-transform:uppercase;white-space:nowrap;\">")
         .append("Level ").append(n.level()).append("&nbsp;&nbsp;&middot;&nbsp;&nbsp;").append(esc(n.percent()))
         .append("</td></tr></table>");

        h.append("<h1 class=\"h1\" style=\"margin:0 0 12px;").append(font(32, 800, INK))
         .append("line-height:38px;letter-spacing:-0.03em;\">").append(esc(headline(n))).append("</h1>")
         .append("<p style=\"margin:0 auto;max-width:440px;").append(font(16, 400, INK_2)).append("line-height:24px;\">")
         .append("Nice work, ").append(esc(displayName(n.recipientName()))).append(". ").append(esc(relation(n)))
         .append("</p>")
         .append("</td></tr></table></td></tr>");
    }

    /** 104px circle: a 3px Unis-blue ring around the photo (or their initial). */
    private static void avatar(StringBuilder h, ReferralNotice n) {
        int size = 96;
        h.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 auto;\"><tr>")
         .append("<td bgcolor=\"").append(ACCENT).append("\" style=\"background-color:").append(ACCENT)
         .append(";padding:4px;border-radius:999px;\">")
         .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>");
        String url = n.newMemberPhoto();
        if (url != null) {
            h.append("<td width=\"").append(size).append("\" height=\"").append(size)
             .append("\" background=\"").append(attr(url)).append("\" style=\"width:").append(size).append("px;height:").append(size)
             .append("px;border-radius:999px;background-color:").append(CARD_2)
             .append(";background-image:url('").append(cssUrl(url)).append("');background-size:cover;background-position:center;")
             .append("font-size:0;line-height:0;\">&nbsp;</td>");
        } else {
            h.append("<td width=\"").append(size).append("\" height=\"").append(size)
             .append("\" align=\"center\" valign=\"middle\" bgcolor=\"").append(MONO_BG).append("\" style=\"width:").append(size)
             .append("px;height:").append(size).append("px;border-radius:999px;background-color:").append(MONO_BG).append(";")
             .append(font(40, 800, MONO_INK)).append("line-height:").append(size).append("px;\">")
             .append(n.newMemberName() != null ? esc(initial(n.newMemberName())) : "+1")
             .append("</td>");
        }
        h.append("</tr></table></td></tr></table>");
    }

    /** The big number: what the reader gets from this member, every month. */
    private static void boost(StringBuilder h, ReferralNotice n) {
        h.append("<tr><td class=\"px\" style=\"padding:0 24px 16px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(CARD)
         .append("\" style=\"background-color:").append(CARD).append(";border:1px solid ").append(LINE).append(";border-radius:20px;\">")
         .append("<tr>")
         .append("<td class=\"stack pct\" width=\"150\" align=\"center\" valign=\"middle\" style=\"width:150px;padding:26px 0 26px 22px;")
         .append(font(68, 800, GOLD)).append("line-height:72px;letter-spacing:-0.04em;\">").append(esc(n.percent())).append("</td>")
         .append("<td class=\"stack stacktext\" valign=\"middle\" style=\"padding:24px 26px 24px 18px;\">")
         .append("<p style=\"margin:0 0 6px;").append(font(18, 700, INK)).append("line-height:24px;letter-spacing:-0.01em;\">")
         .append("of ").append(esc(theirs(n))).append(" monthly Community Share is yours</p>")
         .append("<p style=\"margin:0;").append(font(14, 400, INK_2)).append("line-height:21px;\">")
         .append("Every month they listen on Unis, part of what Unis shares with the community comes to you, for as long as they’re on Unis. ")
         .append("Your place in their chain is permanent.</p>")
         .append("</td></tr></table></td></tr>");
    }

    /** What still has to happen before the share counts or can be paid. */
    private static void notes(StringBuilder h, ReferralNotice n) {
        boolean memberNote = !n.newMemberPhoneVerified();
        boolean readerNote = !n.recipientPhoneVerified();
        if (!memberNote && !readerNote) return;

        h.append("<tr><td class=\"px\" style=\"padding:0 24px 16px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border:1px solid ")
         .append(LINE).append(";border-radius:16px;\">");
        if (memberNote) {
            String text = "Their listening starts counting once they verify their phone number in Unis."
                + (n.level() == 1 ? " A quick nudge from you helps." : "");
            noteRow(h, text);
        }
        if (readerNote) {
            noteRow(h, "Verify your own phone number in Unis so your share can be paid to you.");
        }
        h.append("</table></td></tr>");
    }

    private static void noteRow(StringBuilder h, String text) {
        h.append("<tr>")
         .append("<td width=\"34\" valign=\"top\" style=\"width:34px;padding:15px 0 0 18px;").append(font(14, 800, ACCENT_INK))
         .append("line-height:20px;\">&#9679;</td>")
         .append("<td valign=\"top\" style=\"padding:14px 18px 14px 4px;").append(font(14, 400, INK_2)).append("line-height:21px;\">")
         .append(esc(text)).append("</td></tr>");
    }

    /** The reader's three levels, with the one that just grew lit up. */
    private static void network(StringBuilder h, ReferralNotice n) {
        int[] counts = { n.level1Count(), n.level2Count(), n.level3Count() };
        // The new member is already counted; never show the level that just grew as empty.
        counts[n.level() - 1] = Math.max(1, counts[n.level() - 1]);
        int total = counts[0] + counts[1] + counts[2];
        String[] pct = { "10%", "5%", "2%" };

        h.append("<tr><td class=\"px\" style=\"padding:14px 24px 8px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
         .append("<td align=\"left\" style=\"").append(font(13, 700, INK_3)).append("letter-spacing:0.08em;text-transform:uppercase;\">Your network</td>")
         .append("<td align=\"right\" style=\"").append(font(13, 600, INK_2)).append("\">")
         .append(num(total)).append(total == 1 ? " person" : " people").append("</td>")
         .append("</tr></table></td></tr>");

        h.append("<tr><td class=\"px\" style=\"padding:0 18px 20px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>");
        for (int i = 0; i < 3; i++) {
            boolean lit = i == n.level() - 1;
            String bg = lit ? MONO_BG : CARD;
            String border = lit ? ACCENT : LINE;
            h.append("<td width=\"33%\" valign=\"top\" style=\"width:33%;padding:0 6px;\">")
             .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(bg)
             .append("\" style=\"background-color:").append(bg).append(";border:1px solid ").append(border).append(";border-radius:16px;\">")
             .append("<tr><td align=\"center\" style=\"padding:16px 6px 15px;\">")
             .append("<p style=\"margin:0 0 4px;").append(font(12, 700, lit ? ACCENT_INK : INK_3)).append("letter-spacing:0.04em;\">")
             .append("Level ").append(i + 1).append("</p>")
             .append("<p class=\"tilenum\" style=\"margin:0;").append(font(30, 800, INK)).append("line-height:36px;letter-spacing:-0.02em;\">")
             .append(num(counts[i])).append("</p>")
             .append("<p style=\"margin:4px 0 0;").append(font(12, lit ? 700 : 500, lit ? UP : INK_3)).append("\">")
             .append(lit ? "+1 new" : pct[i] + " each").append("</p>")
             .append("</td></tr></table></td>");
        }
        h.append("</tr></table></td></tr>");
    }

    private static void cta(StringBuilder h, ReferralNotice n) {
        h.append("<tr><td class=\"px\" align=\"center\" style=\"padding:14px 24px 26px;\">");
        if (n.prelaunch()) {
            h.append("<p style=\"margin:0 0 6px;").append(font(22, 800, INK)).append("letter-spacing:-0.02em;\">Unis opens soon</p>")
             .append("<p style=\"margin:0 auto;max-width:440px;").append(font(15, 400, INK_2)).append("line-height:22px;\">")
             .append("Your network is already growing. We’ll email you the day Unis opens, and everyone you brought in will be there with you.</p>");
        } else {
            h.append("<p style=\"margin:0 0 6px;").append(font(22, 800, INK)).append("letter-spacing:-0.02em;\">Watch your network grow</p>")
             .append("<p style=\"margin:0 auto 18px;max-width:440px;").append(font(15, 400, INK_2)).append("line-height:22px;\">")
             .append("Your referrals, their listening this month and what you’ve earned are all in Earnings.</p>");
            button(h, link(n, "/earnings?tab=referrals"), "Open Unis", ACCENT, "#ffffff");
        }
        h.append("</td></tr>");
    }

    /** The reader's own code and link, to keep it going. */
    private static void code(StringBuilder h, ReferralNotice n) {
        String code = n.referralCode();
        if (code == null || code.isBlank()) return;
        String joinUrl = trimSlash(n.appBaseUrl()) + "/join/" + code;
        String shown = joinUrl.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");

        h.append("<tr><td class=\"px\" style=\"padding:0 24px 30px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(CARD)
         .append("\" style=\"background-color:").append(CARD).append(";border:1px solid ").append(LINE).append(";border-radius:20px;\">")
         .append("<tr><td align=\"center\" style=\"padding:24px 22px 24px;\">")
         .append("<p style=\"margin:0 0 6px;").append(font(13, 700, ACCENT_INK)).append("letter-spacing:0.08em;text-transform:uppercase;\">Keep it going</p>")
         .append("<p style=\"margin:0 auto 16px;max-width:420px;").append(font(15, 400, INK_2)).append("line-height:22px;\">")
         .append("Everyone who joins with your code is Level 1 for you, at 10%. Their invites count for you too.</p>")
         .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 auto 12px;\"><tr>")
         .append("<td class=\"code\" bgcolor=\"").append(CARD_2).append("\" style=\"background-color:").append(CARD_2)
         .append(";border:1px dashed #3a4466;border-radius:12px;padding:12px 22px;font-family:").append(MONO)
         .append(";font-size:24px;font-weight:700;color:").append(INK).append(";letter-spacing:0.12em;\">")
         .append(esc(code)).append("</td></tr></table>")
         .append("<p style=\"margin:0;").append(font(13, 600, INK_3)).append("\">")
         .append("<a href=\"").append(attr(joinUrl)).append("\" style=\"color:").append(ACCENT_INK).append(";text-decoration:none;\">")
         .append(esc(shown)).append("</a></p>")
         .append("</td></tr></table></td></tr>");
    }

    private static void footer(StringBuilder h, ReferralNotice n) {
        h.append("<tr><td class=\"px\" align=\"center\" style=\"padding:18px 24px 0;border-top:1px solid ").append(LINE).append(";\">")
         .append("<p style=\"margin:0 0 10px;").append(font(12, 400, INK_3)).append("line-height:18px;\">")
         .append("You’re getting this because someone joined Unis through your referral network.</p>")
         .append("<p style=\"margin:0 0 10px;").append(font(12, 600, INK_3)).append("\">")
         .append("<a href=\"").append(attr(link(n, "/"))).append("\" style=\"color:").append(INK_2).append(";text-decoration:none;\">Open Unis</a>")
         .append("&nbsp;&nbsp;&middot;&nbsp;&nbsp;")
         .append("<a href=\"").append(attr(n.unsubscribeUrl())).append("\" style=\"color:").append(INK_2).append(";text-decoration:underline;\">Unsubscribe</a>")
         .append("</p>")
         .append("<p style=\"margin:0;").append(font(11, 400, INK_3)).append("line-height:16px;\">Unis Music Corporation");
        if (n.postalAddress() != null && !n.postalAddress().isBlank()) {
            h.append("<br>").append(esc(n.postalAddress()));
        }
        h.append("</p></td></tr>");
    }

    // ── Pieces ────────────────────────────────────────────────────────────────

    /** A "bulletproof" button: a padded table cell, so it keeps its shape in every mail app. */
    private static void button(StringBuilder h, String url, String label, String bg, String fg) {
        h.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"display:inline-table;\"><tr>")
         .append("<td bgcolor=\"").append(bg).append("\" style=\"border-radius:999px;background-color:").append(bg).append(";\">")
         .append("<a href=\"").append(attr(url)).append("\" style=\"display:inline-block;padding:14px 30px;border-radius:999px;")
         .append(font(16, 700, fg)).append("text-decoration:none;\">").append(esc(label)).append("</a>")
         .append("</td></tr></table>");
    }

    private static String link(ReferralNotice n, String path) {
        return trimSlash(n.appBaseUrl()) + path + (path.contains("?") ? "&" : "?") + UTM;
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.replaceAll("/+$", "");
    }

    private static String num(int n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }

    private static String initial(String name) {
        String s = name == null ? "" : name.trim();
        return s.isEmpty() ? "?" : s.substring(0, s.offsetByCodePoints(0, 1)).toUpperCase(Locale.US);
    }

    private static String displayName(String name) {
        return name == null || name.isBlank() ? "there" : name.trim();
    }

    // ── Plain-text part ───────────────────────────────────────────────────────

    private static String plainText(ReferralNotice n, String subject) {
        StringBuilder t = new StringBuilder();
        t.append("UNIS · New in your network\n\n");
        t.append(subject).append("\n\n");
        t.append("Nice work, ").append(displayName(n.recipientName())).append(". ").append(relation(n)).append("\n\n");
        t.append("You now get ").append(n.percent()).append(" of ").append(theirs(n))
         .append(" monthly Community Share. Every month they listen on Unis, part of what Unis shares with the community ")
         .append("comes to you, for as long as they're on Unis. Your place in their chain is permanent.\n\n");
        if (!n.newMemberPhoneVerified()) {
            t.append("- Their listening starts counting once they verify their phone number in Unis.\n");
        }
        if (!n.recipientPhoneVerified()) {
            t.append("- Verify your own phone number in Unis so your share can be paid to you.\n");
        }
        if (!n.newMemberPhoneVerified() || !n.recipientPhoneVerified()) t.append("\n");

        int[] counts = { n.level1Count(), n.level2Count(), n.level3Count() };
        counts[n.level() - 1] = Math.max(1, counts[n.level() - 1]);
        t.append("Your network: Level 1: ").append(counts[0]).append(" · Level 2: ").append(counts[1])
         .append(" · Level 3: ").append(counts[2]).append("\n\n");

        if (n.prelaunch()) {
            t.append("Unis opens soon. We'll email you the day it opens.\n\n");
        } else {
            t.append("See your network: ").append(link(n, "/earnings?tab=referrals")).append("\n\n");
        }
        if (n.referralCode() != null && !n.referralCode().isBlank()) {
            t.append("Your code: ").append(n.referralCode()).append("\n")
             .append(trimSlash(n.appBaseUrl())).append("/join/").append(n.referralCode()).append("\n\n");
        }
        t.append("Unsubscribe: ").append(n.unsubscribeUrl()).append("\n");
        t.append("Unis Music Corporation");
        if (n.postalAddress() != null && !n.postalAddress().isBlank()) t.append(", ").append(n.postalAddress());
        t.append("\n");
        return t.toString();
    }
}
