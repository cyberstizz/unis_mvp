package com.unis.service.digest;

import com.unis.service.digest.WeeklyDigest.Pick;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Draws the Sunday email.
 *
 * <p><b>Email rules this follows</b> (mail apps are far stricter than
 * browsers): table layout, inline styles, a 600px column that shrinks on
 * phones, no SVG (Gmail drops it), no dependence on web fonts (Inter loads in
 * Apple Mail; everything else falls back to the system face), artist photos
 * cropped with CSS backgrounds rather than object-fit (Gmail ignores
 * object-fit, so a tall photo would otherwise be squashed), a plain-text
 * part, and a hidden preheader so the inbox preview reads well.
 *
 * <p><b>Look</b>: a dark card stack, declared dark so mail apps don't invert
 * it. The memorable part is the hero: the area's number one artist, full
 * bleed, with their name over the photo. Everything after it is quieter.
 *
 * <p>Pure Java — no Spring — so it renders anywhere (the admin preview,
 * tests, a local sample).
 */
public final class WeeklyDigestRenderer {

    public record Rendered(String subject, String preheader, String html, String text) {}

    // ── Palette ───────────────────────────────────────────────────────────────
    private static final String BG = "#07080c";
    private static final String CARD = "#10131b";
    private static final String CARD_2 = "#171b26";
    private static final String LINE = "#242a3a";
    private static final String INK = "#f4f5f8";
    private static final String INK_2 = "#b6bbc8";
    private static final String INK_3 = "#7d8396";
    private static final String ACCENT = "#1E50EB";      // the UnisMark blue
    private static final String ACCENT_INK = "#8fa8ff";  // the same blue, legible as text on dark
    private static final String MONO_BG = "#14204d";     // no-photo tiles
    private static final String MONO_INK = "#7f95e8";
    private static final String GOLD = "#f5c242";
    private static final String UP = "#34d399";
    private static final String DOWN = "#f87171";

    private static final String FONT =
        "'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif";

    private static final String UTM = "utm_source=unis_weekly&utm_medium=email&utm_campaign=sunday";

    private WeeklyDigestRenderer() {}

    // ═════════════════════════════════════════════════════════════════════════
    public static Rendered render(WeeklyDigest d) {
        String area = d.areaName();
        Pick top = d.topArtist();
        boolean live = d.live();
        boolean tonight = d.closesTonight();

        String subject;
        if (live) {
            subject = top.name() + " leads " + area + (tonight ? " — the week closes tonight" : " this week");
        } else {
            subject = "This week in " + area + " on Unis";
        }

        String preheader = live
            ? "The top 5, the number one song, and who leads all of Unis"
                + (tonight ? " going into the last day." : " so far.")
            : "The chart is wide open. The first play, like or vote of the week can take number one.";

        StringBuilder h = new StringBuilder(24_000);
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
         .append(".hero{height:400px!important;}")
         .append(".heroname{font-size:30px!important;line-height:34px!important;}")
         .append(".h1{font-size:26px!important;line-height:32px!important;}")
         .append(".artcell{width:96px!important;}")
         .append(".art{width:96px!important;height:96px!important;}")
         .append(".songtext{padding-left:14px!important;}")
         .append("}")
         .append("</style></head>")
         .append("<body style=\"margin:0;padding:0;background:").append(BG).append(";\" bgcolor=\"").append(BG).append("\">");

        // Hidden preheader, then spacer characters so the inbox preview
        // doesn't run on into the body text.
        h.append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;mso-hide:all;\">")
         .append(esc(preheader))
         .append("&#8204;&nbsp;".repeat(60))
         .append("</div>");

        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(BG)
         .append("\" style=\"background:").append(BG).append(";\"><tr><td align=\"center\" style=\"padding:24px 10px 40px;\">")
         .append("<table role=\"presentation\" class=\"wrap\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:600px;max-width:600px;\">");

        header(h, d);
        intro(h, d, live, tonight);
        if (top != null) hero(h, d, top, live, tonight);
        if (d.topSong() != null) song(h, d, d.topSong(), live);
        if (d.unisArtist() != null) unis(h, d, top, d.unisArtist());
        if (d.topFive() != null && d.topFive().size() > 1) topFive(h, d, live);
        personal(h, d, tonight);
        closer(h, d, live, tonight);
        footer(h, d);

        h.append("</table></td></tr></table></body></html>");

        return new Rendered(subject, preheader, h.toString(), plainText(d, live, tonight, subject));
    }

    // ── Sections ──────────────────────────────────────────────────────────────

    private static void header(StringBuilder h, WeeklyDigest d) {
        h.append("<tr><td class=\"px\" style=\"padding:4px 24px 26px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
         .append("<td align=\"left\" style=\"vertical-align:middle;\">")
         // Text wordmark only — the brand mark is being redesigned.
         .append("<a href=\"").append(attr(link(d, "/"))).append("\" style=\"").append(font(22, 800, INK))
         .append("line-height:32px;letter-spacing:-0.03em;text-decoration:none;\">Unis</a>")
         .append("</td>")
         .append("<td align=\"right\" style=\"vertical-align:middle;").append(font(12, 600, INK_3)).append("\">")
         .append(esc(d.weekLabel())).append("</td>")
         .append("</tr></table></td></tr>");
    }

    private static void intro(StringBuilder h, WeeklyDigest d, boolean live, boolean tonight) {
        String name = displayName(d.recipientName());
        String area = d.areaName();
        String headline = live ? "Who’s running " + area + " this week" : "The chart is open in " + area;
        String lede;
        if (live) {
            lede = "Morning, " + name + ". "
                + (tonight ? "The week closes tonight at midnight. Here’s where " + area + " stands going into the last day."
                           : "The week closes Sunday at midnight. Here’s where " + area + " stands so far.");
        } else {
            lede = "Morning, " + name + ". Nobody has scored in " + area
                + " this week yet, so the first play, like or vote can take number one.";
        }

        h.append("<tr><td class=\"px\" style=\"padding:0 24px 22px;\">")
         .append("<p style=\"margin:0 0 8px;").append(font(13, 600, ACCENT_INK)).append("\">Sunday on Unis</p>")
         .append("<h1 class=\"h1\" style=\"margin:0 0 12px;").append(font(32, 800, INK))
         .append("line-height:38px;letter-spacing:-0.03em;\">").append(esc(headline)).append("</h1>")
         .append("<p style=\"margin:0;").append(font(16, 400, INK_2)).append("line-height:24px;\">").append(esc(lede)).append("</p>")
         .append("</td></tr>");
    }

    /** The area's number one artist, full bleed. */
    private static void hero(StringBuilder h, WeeklyDigest d, Pick top, boolean live, boolean tonight) {
        String img = top.imageUrl();
        String kicker = live ? "Number one in " + d.areaName() : "Last week’s number one in " + d.areaName();
        String url = link(d, "/artist/" + top.id());

        h.append("<tr><td class=\"px\" style=\"padding:0 24px 16px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(CARD_2)
         .append("\" style=\"border-radius:22px;background-color:").append(CARD_2).append(";\">")
         .append("<tr><td class=\"hero\" height=\"440\" valign=\"bottom\"");
        if (img != null) {
            h.append(" background=\"").append(attr(img)).append("\" style=\"height:440px;border-radius:22px;background-color:").append(CARD_2)
             .append(";background-image:url('").append(cssUrl(img)).append("');background-size:cover;background-position:center 25%;\">");
        } else {
            h.append(" style=\"height:440px;border-radius:22px;background-color:").append(MONO_BG).append(";\">");
        }

        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">");
        if (img == null) {
            h.append("<tr><td align=\"center\" style=\"padding:36px 0 0;").append(font(140, 800, "#2b3a78"))
             .append("line-height:140px;\">").append(esc(initial(top.name()))).append("</td></tr>");
        }
        h.append("<tr><td style=\"padding:22px 24px 24px;background-color:rgba(7,8,12,0.62);border-radius:0 0 22px 22px;\">")
         .append("<p style=\"margin:0 0 6px;").append(font(13, 700, GOLD)).append("\">&#9733;&nbsp; ").append(esc(kicker)).append("</p>")
         .append("<p class=\"heroname\" style=\"margin:0 0 8px;").append(font(38, 800, INK))
         .append("line-height:42px;letter-spacing:-0.03em;\"><a href=\"").append(attr(url)).append("\" style=\"color:").append(INK)
         .append(";text-decoration:none;\">").append(esc(top.name())).append("</a></p>")
         .append("<p style=\"margin:0 0 4px;").append(font(14, 400, INK_2)).append("\">").append(esc(statLine(top))).append("</p>");
        if (top.leadOverNext() != null && top.leadOverNext() > 0) {
            h.append("<p style=\"margin:0 0 16px;").append(font(14, 600, ACCENT_INK)).append("\">")
             .append(live ? "Ahead by " : "Won by ").append(num(top.leadOverNext()))
             .append(top.leadOverNext() == 1 ? " point" : " points");
            if (live && tonight) h.append(" with a day to go");
            h.append("</p>");
        } else {
            h.append("<div style=\"height:12px;line-height:12px;font-size:12px;\">&nbsp;</div>");
        }
        button(h, url, "Listen to " + top.name(), ACCENT, "#ffffff");
        h.append("</td></tr></table></td></tr></table></td></tr>");
    }

    /** The area's number one song: artwork left, words right (smaller artwork on phones). */
    private static void song(StringBuilder h, WeeklyDigest d, Pick s, boolean live) {
        String url = link(d, "/song/" + s.id());
        h.append("<tr><td class=\"px\" style=\"padding:0 24px 16px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(CARD)
         .append("\" style=\"border-radius:20px;background-color:").append(CARD).append(";border:1px solid ").append(LINE).append(";\">")
         .append("<tr><td style=\"padding:18px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
         .append("<td class=\"artcell\" width=\"140\" valign=\"top\" style=\"width:140px;\">")
         .append("<a href=\"").append(attr(url)).append("\" style=\"text-decoration:none;\">");
        artwork(h, s.imageUrl(), s.name());
        h.append("</a></td>")
         .append("<td class=\"songtext\" valign=\"middle\" style=\"padding:0 0 0 18px;\">")
         .append("<p style=\"margin:0 0 6px;").append(font(13, 600, ACCENT_INK)).append("\">")
         .append(esc(live ? "Number one song in " + d.areaName() : "Last week’s number one song")).append("</p>")
         .append("<p style=\"margin:0 0 4px;").append(font(22, 800, INK)).append("line-height:27px;letter-spacing:-0.02em;\">")
         .append("<a href=\"").append(attr(url)).append("\" style=\"color:").append(INK).append(";text-decoration:none;\">").append(esc(s.name())).append("</a></p>");
        if (s.artistName() != null && !s.artistName().isBlank()) {
            String artistUrl = s.artistId() != null ? link(d, "/artist/" + s.artistId()) : url;
            h.append("<p style=\"margin:0 0 10px;").append(font(15, 400, INK_2)).append("\">")
             .append("<a href=\"").append(attr(artistUrl)).append("\" style=\"color:").append(INK_2).append(";text-decoration:none;\">")
             .append(esc(s.artistName())).append("</a></p>");
        }
        h.append("<p style=\"margin:0 0 14px;").append(font(13, 400, INK_3)).append("\">").append(esc(statLine(s))).append("</p>");
        button(h, url, "Play it on Unis", CARD_2, INK);
        h.append("</td></tr></table></td></tr></table></td></tr>");
    }

    /** Number one on all of Unis — or one line when it's the same artist as the hero. */
    private static void unis(StringBuilder h, WeeklyDigest d, Pick areaTop, Pick u) {
        boolean unisLive = d.unisLive();
        h.append("<tr><td class=\"px\" style=\"padding:0 24px 16px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(CARD)
         .append("\" style=\"border-radius:20px;background-color:").append(CARD).append(";border:1px solid ").append(LINE).append(";\">")
         .append("<tr><td style=\"padding:16px 18px;\">");

        boolean sameAsHero = areaTop != null && areaTop.id().equals(u.id()) && unisLive == d.areaLive();
        if (sameAsHero) {
            h.append("<p style=\"margin:0;").append(font(15, 400, INK_2)).append("line-height:22px;\">")
             .append("<span style=\"color:").append(GOLD).append(";\">&#9733;</span>&nbsp; ")
             .append("<strong style=\"color:").append(INK).append(";font-weight:700;\">").append(esc(u.name())).append("</strong>")
             .append(esc(unisLive ? " also leads all of Unis this week." : " also finished first on all of Unis.")).append("</p>");
        } else {
            String url = link(d, "/artist/" + u.id());
            h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
             .append("<td width=\"60\" valign=\"middle\" style=\"width:60px;\"><a href=\"").append(attr(url)).append("\" style=\"text-decoration:none;\">");
            photoTile(h, u.imageUrl(), u.name(), 56, 28);
            h.append("</a></td><td valign=\"middle\" style=\"padding:0 0 0 14px;\">")
             .append("<p style=\"margin:0 0 3px;").append(font(13, 600, GOLD)).append("\">")
             .append(esc(unisLive ? "Number one on all of Unis" : "Last week’s number one on Unis")).append("</p>")
             .append("<p style=\"margin:0;").append(font(18, 800, INK)).append("line-height:23px;\"><a href=\"").append(attr(url))
             .append("\" style=\"color:").append(INK).append(";text-decoration:none;\">").append(esc(u.name())).append("</a></p>")
             .append("</td><td align=\"right\" valign=\"middle\" style=\"white-space:nowrap;").append(font(15, 700, INK)).append("\">")
             .append(num(u.points())).append("<span style=\"").append(font(12, 400, INK_3)).append("\"> pts</span></td></tr></table>");
        }
        h.append("</td></tr></table></td></tr>");
    }

    private static void topFive(StringBuilder h, WeeklyDigest d, boolean live) {
        h.append("<tr><td class=\"px\" style=\"padding:8px 24px 16px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(CARD)
         .append("\" style=\"border-radius:20px;background-color:").append(CARD).append(";border:1px solid ").append(LINE).append(";\">")
         .append("<tr><td style=\"padding:18px 20px 8px;\">")
         .append("<p style=\"margin:0 0 3px;").append(font(18, 800, INK)).append("letter-spacing:-0.01em;\">")
         .append(esc((live ? "The top 5 in " : "Last week’s top 5 in ") + d.areaName())).append("</p>")
         .append("<p style=\"margin:0 0 6px;").append(font(13, 400, INK_3)).append("\">Points from votes, plays and likes.</p>")
         .append("</td></tr>");

        for (Pick p : d.topFive()) {
            String url = link(d, "/artist/" + p.id());
            h.append("<tr><td style=\"padding:0 20px;\">")
             .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-top:1px solid ").append(LINE).append(";\"><tr>")
             .append("<td width=\"30\" valign=\"middle\" style=\"width:30px;padding:12px 0;").append(font(20, 800, p.rank() == 1 ? GOLD : INK_2)).append("\">")
             .append(p.rank()).append("</td>")
             .append("<td width=\"46\" valign=\"middle\" style=\"width:46px;padding:10px 0;\"><a href=\"").append(attr(url)).append("\" style=\"text-decoration:none;\">");
            photoTile(h, p.imageUrl(), p.name(), 36, 18);
            h.append("</a></td>")
             .append("<td valign=\"middle\" style=\"padding:12px 0;\">")
             .append("<a href=\"").append(attr(url)).append("\" style=\"").append(font(15, 600, INK)).append("text-decoration:none;\">")
             .append(esc(p.name())).append("</a>").append(movement(p)).append("</td>")
             .append("<td align=\"right\" valign=\"middle\" style=\"padding:12px 0;white-space:nowrap;").append(font(15, 700, INK)).append("\">")
             .append(num(p.points())).append("<span style=\"").append(font(12, 400, INK_3)).append("\"> pts</span></td>")
             .append("</tr></table></td></tr>");
        }

        String chart = link(d, "/charts?type=artist&period=weekly&genre=all"
            + (d.areaPlaceKey() != null ? "&place=" + d.areaPlaceKey() : ""));
        h.append("<tr><td style=\"padding:6px 20px 18px;\">")
         .append("<a href=\"").append(attr(chart)).append("\" style=\"").append(font(14, 600, ACCENT_INK)).append("text-decoration:none;\">See the full chart &rarr;</a>")
         .append("</td></tr></table></td></tr>");
    }

    private static void personal(StringBuilder h, WeeklyDigest d, boolean tonight) {
        WeeklyDigest.Personal p = d.personal();
        if (p == null) return;
        String area = d.areaName();
        String stillCounts = tonight ? "Every play, like and vote before midnight still counts."
                                     : "Every play, like and vote until Sunday at midnight counts.";

        String line;
        String sub;
        if (d.recipientIsArtist()) {
            if (p.artistRank() != null && p.artistRank() == 1) {
                line = "You’re number one in " + area + " this week.";
                sub = "Hold on to it. " + stillCounts;
            } else if (p.artistRank() != null) {
                line = "You’re #" + p.artistRank() + " in " + area + " this week.";
                sub = stillCounts + " Share your music today.";
            } else {
                line = "You haven’t scored in " + area + " this week yet.";
                sub = "One play from a fan puts you on the chart. Share your music today.";
            }
        } else if (p.playsThisWeek() > 0 || p.votesThisWeek() > 0) {
            line = "Your week: " + num(p.playsThisWeek()) + (p.playsThisWeek() == 1 ? " play" : " plays")
                + " and " + num(p.votesThisWeek()) + (p.votesThisWeek() == 1 ? " vote." : " votes.");
            sub = "Every one of them moved the chart in " + area + ".";
        } else {
            line = "You haven’t played anything this week.";
            sub = tonight ? "There’s still today. Your plays and votes count until midnight."
                          : "Your plays and votes count until Sunday at midnight.";
        }

        h.append("<tr><td class=\"px\" style=\"padding:0 24px 16px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(CARD)
         .append("\" style=\"border-radius:16px;background-color:").append(CARD).append(";border-left:3px solid ").append(ACCENT).append(";\">")
         .append("<tr><td style=\"padding:16px 18px;\">")
         .append("<p style=\"margin:0 0 4px;").append(font(16, 700, INK)).append("\">").append(esc(line)).append("</p>")
         .append("<p style=\"margin:0;").append(font(14, 400, INK_2)).append("line-height:21px;\">").append(esc(sub)).append("</p>")
         .append("</td></tr></table></td></tr>");
    }

    private static void closer(StringBuilder h, WeeklyDigest d, boolean live, boolean tonight) {
        String title;
        String body;
        String cta;
        if (live) {
            title = tonight ? "The week closes at midnight." : "The week closes Sunday at midnight.";
            body = "One vote can still decide who wins " + d.areaName() + ".";
            cta = tonight ? "Vote before midnight" : "Vote this week";
        } else {
            title = "This week is still up for grabs.";
            body = "Be the first vote in " + d.areaName() + " this week.";
            cta = "Cast the first vote";
        }
        h.append("<tr><td class=\"px\" align=\"center\" style=\"padding:20px 24px 30px;\">")
         .append("<p style=\"margin:0 0 6px;").append(font(22, 800, INK)).append("letter-spacing:-0.02em;\">").append(esc(title)).append("</p>")
         .append("<p style=\"margin:0 0 18px;").append(font(15, 400, INK_2)).append("\">").append(esc(body)).append("</p>");
        button(h, link(d, "/voteawards"), cta, ACCENT, "#ffffff");
        h.append("</td></tr>");
    }

    private static void footer(StringBuilder h, WeeklyDigest d) {
        h.append("<tr><td class=\"px\" align=\"center\" style=\"padding:18px 24px 0;border-top:1px solid ").append(LINE).append(";\">")
         .append("<p style=\"margin:0 0 10px;").append(font(12, 400, INK_3)).append("line-height:18px;\">")
         .append("You’re getting this because you’re part of Unis in ").append(esc(d.areaName()))
         .append(". It arrives Sunday mornings.</p>")
         .append("<p style=\"margin:0 0 10px;").append(font(12, 600, INK_3)).append("\">")
         .append("<a href=\"").append(attr(link(d, "/"))).append("\" style=\"color:").append(INK_2).append(";text-decoration:none;\">Open Unis</a>")
         .append("&nbsp;&nbsp;&middot;&nbsp;&nbsp;")
         .append("<a href=\"").append(attr(d.unsubscribeUrl())).append("\" style=\"color:").append(INK_2).append(";text-decoration:underline;\">Unsubscribe</a>")
         .append("</p>")
         .append("<p style=\"margin:0;").append(font(11, 400, INK_3)).append("line-height:16px;\">Unis Music Corporation");
        if (d.postalAddress() != null && !d.postalAddress().isBlank()) {
            h.append("<br>").append(esc(d.postalAddress()));
        }
        h.append("</p></td></tr>");
    }

    // ── Pieces ────────────────────────────────────────────────────────────────

    /** A "bulletproof" button: a padded table cell, so it keeps its shape in every mail app. */
    private static void button(StringBuilder h, String url, String label, String bg, String fg) {
        h.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"display:inline-table;\"><tr>")
         .append("<td bgcolor=\"").append(bg).append("\" style=\"border-radius:999px;background-color:").append(bg).append(";\">")
         .append("<a href=\"").append(attr(url)).append("\" style=\"display:inline-block;padding:13px 24px;border-radius:999px;")
         .append(font(15, 700, fg)).append("text-decoration:none;\">").append(esc(label)).append("</a>")
         .append("</td></tr></table>");
    }

    /**
     * Song artwork: a real image (artwork is square, and a real image shows in
     * every mail app, Outlook included), or the title's first letter on brand
     * blue when there is none.
     */
    private static void artwork(StringBuilder h, String url, String name) {
        if (url != null) {
            h.append("<img class=\"art\" src=\"").append(attr(url)).append("\" width=\"140\" height=\"140\" alt=\"")
             .append(esc(name)).append("\" style=\"display:block;width:140px;height:140px;border:0;outline:none;border-radius:14px;background-color:")
             .append(CARD_2).append(";\">");
            return;
        }
        h.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
         .append("<td width=\"140\" height=\"140\" align=\"center\" valign=\"middle\" style=\"width:140px;height:140px;border-radius:14px;background-color:")
         .append(MONO_BG).append(";").append(font(48, 800, MONO_INK)).append("\">").append(esc(initial(name))).append("</td>")
         .append("</tr></table>");
    }

    /**
     * An artist photo cropped to a square or circle. A CSS background (cover)
     * because Gmail ignores object-fit and artist photos come in every shape;
     * the HTML background attribute covers older apps. No photo: the first
     * letter of the name on brand blue.
     */
    private static void photoTile(StringBuilder h, String url, String name, int size, int radius) {
        h.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>");
        if (url != null) {
            h.append("<td width=\"").append(size).append("\" height=\"").append(size)
             .append("\" background=\"").append(attr(url)).append("\" style=\"width:").append(size).append("px;height:").append(size)
             .append("px;border-radius:").append(radius).append("px;background-color:").append(CARD_2)
             .append(";background-image:url('").append(cssUrl(url)).append("');background-size:cover;background-position:center;")
             .append("font-size:0;line-height:0;\">&nbsp;</td>");
        } else {
            h.append("<td width=\"").append(size).append("\" height=\"").append(size)
             .append("\" align=\"center\" valign=\"middle\" style=\"width:").append(size).append("px;height:").append(size)
             .append("px;border-radius:").append(radius).append("px;background-color:").append(MONO_BG).append(";")
             .append(font(Math.max(13, size * 2 / 5), 800, MONO_INK)).append("\">").append(esc(initial(name))).append("</td>");
        }
        h.append("</tr></table>");
    }

    private static String movement(Pick p) {
        if (p.isNew()) {
            return "<span style=\"" + font(11, 700, ACCENT_INK) + "padding-left:8px;\">New</span>";
        }
        if (p.movement() == null || p.movement() == 0) return "";
        boolean up = p.movement() > 0;
        return "<span style=\"" + font(12, 700, up ? UP : DOWN) + "padding-left:8px;\">"
            + (up ? "&#9650;" : "&#9660;") + "&nbsp;" + Math.abs(p.movement()) + "</span>";
    }

    private static String statLine(Pick p) {
        StringBuilder s = new StringBuilder(num(p.points())).append(p.points() == 1 ? " point" : " points");
        if (p.votes() > 0) s.append(" · ").append(num(p.votes())).append(p.votes() == 1 ? " vote" : " votes");
        if (p.plays() > 0) s.append(" · ").append(num(p.plays())).append(p.plays() == 1 ? " play" : " plays");
        if (p.likes() > 0) s.append(" · ").append(num(p.likes())).append(p.likes() == 1 ? " like" : " likes");
        return s.toString();
    }

    private static String font(int size, int weight, String color) {
        return "font-family:" + FONT + ";font-size:" + size + "px;font-weight:" + weight + ";color:" + color + ";";
    }

    private static String link(WeeklyDigest d, String path) {
        String base = d.appBaseUrl() == null ? "" : d.appBaseUrl().replaceAll("/+$", "");
        return base + path + (path.contains("?") ? "&" : "?") + UTM;
    }

    private static String num(int n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }

    private static String initial(String name) {
        String n = name == null ? "" : name.trim();
        return n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(Locale.US);
    }

    private static String displayName(String name) {
        return name == null || name.isBlank() ? "there" : name.trim();
    }

    /** HTML text escaping. */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Attribute values: spaces in URLs encoded, then escaped. */
    private static String attr(String s) {
        return esc(s == null ? "" : s.replace(" ", "%20"));
    }

    /** A URL that is safe inside url('...') in an inline style attribute. */
    private static String cssUrl(String s) {
        return s.replace(" ", "%20").replace("'", "%27").replace("(", "%28").replace(")", "%29")
                .replace("\"", "%22").replace("&", "&amp;");
    }

    // ── Plain-text part ───────────────────────────────────────────────────────

    private static String plainText(WeeklyDigest d, boolean live, boolean tonight, String subject) {
        StringBuilder t = new StringBuilder();
        t.append("UNIS · ").append(d.weekLabel()).append("\n\n");
        t.append(subject).append("\n\n");
        Pick top = d.topArtist();
        if (top != null) {
            t.append(live ? "Number one in " : "Last week's number one in ").append(d.areaName()).append(": ")
             .append(top.name()).append(" (").append(statLine(top)).append(")\n")
             .append(link(d, "/artist/" + top.id())).append("\n\n");
        }
        if (d.topSong() != null) {
            Pick s = d.topSong();
            t.append(live ? "Number one song: " : "Last week's number one song: ").append(s.name());
            if (s.artistName() != null && !s.artistName().isBlank()) t.append(" by ").append(s.artistName());
            t.append("\n").append(link(d, "/song/" + s.id())).append("\n\n");
        }
        if (d.unisArtist() != null) {
            t.append(d.unisLive() ? "Number one on all of Unis: " : "Last week's number one on Unis: ")
             .append(d.unisArtist().name()).append("\n\n");
        }
        if (d.topFive() != null && d.topFive().size() > 1) {
            t.append(live ? "The top 5 in " : "Last week's top 5 in ").append(d.areaName()).append(":\n");
            for (Pick p : d.topFive()) {
                t.append("  ").append(p.rank()).append(". ").append(p.name()).append(" — ").append(num(p.points())).append(" pts\n");
            }
            t.append("\n");
        }
        if (live) {
            t.append(tonight ? "The week closes at midnight. Vote: " : "The week closes Sunday at midnight. Vote: ");
        } else {
            t.append("Be the first vote this week: ");
        }
        t.append(link(d, "/voteawards")).append("\n\n");
        t.append("Unsubscribe: ").append(d.unsubscribeUrl()).append("\n");
        t.append("Unis Music Corporation");
        if (d.postalAddress() != null && !d.postalAddress().isBlank()) t.append(", ").append(d.postalAddress());
        t.append("\n");
        return t.toString();
    }
}
