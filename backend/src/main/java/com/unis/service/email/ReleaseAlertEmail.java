package com.unis.service.email;

import com.unis.service.ReleaseAlertService.Item;
import com.unis.service.mail.EmailBrand;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.unis.service.mail.EmailBrand.*;

/**
 * The daily release email: "New music from artists you follow".
 *
 * <p>Same rules as the Sunday email: table layout, inline styles, a 600px
 * column, covers drawn as cell backgrounds (Gmail ignores object-fit), a
 * plain-text part, a hidden preheader, and the Unis logo from EmailBrand.
 * Pure Java, no Spring.
 */
public final class ReleaseAlertEmail {

    public record Rendered(String subject, String html, String text) {}

    private static final int MAX_ITEMS = 8;
    private static final String UTM = "utm_source=unis_alerts&utm_medium=email&utm_campaign=release_alert";

    private ReleaseAlertEmail() {}

    public static Rendered render(String appBaseUrl, Function<String, String> media, List<Item> items,
                                  String unsubscribeUrl, String manageUrl, String postalAddress) {
        String app = appBaseUrl == null ? "" : appBaseUrl.replaceAll("/+$", "");
        String subject = subject(items);
        String preheader = items.size() == 1
            ? items.get(0).artistName() + " " + verb(items.get(0)) + ": " + items.get(0).title()
            : "New music from " + items.size() + " artists you follow.";

        StringBuilder h = new StringBuilder(12_000);
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
         .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
         .append("<meta name=\"x-apple-disable-message-reformatting\">")
         .append("<meta name=\"color-scheme\" content=\"dark\"><meta name=\"supported-color-schemes\" content=\"dark\">")
         .append("<title>").append(esc(subject)).append("</title>")
         .append("<style>body{margin:0;padding:0;background:").append(BG).append(";}a{text-decoration:none;}")
         .append("@media (max-width:620px){.wrap{width:100%!important;}.px{padding-left:14px!important;padding-right:14px!important;}}</style>")
         .append("</head><body style=\"margin:0;padding:0;background:").append(BG).append(";\" bgcolor=\"").append(BG).append("\">")
         .append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;mso-hide:all;\">").append(esc(preheader))
         .append("&#8204;&nbsp;".repeat(50)).append("</div>")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(BG)
         .append("\" style=\"background:").append(BG).append(";\"><tr><td align=\"center\" style=\"padding:24px 10px 40px;\">")
         .append("<table role=\"presentation\" class=\"wrap\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:600px;max-width:600px;\">");

        EmailBrand.header(h, app, link(app, "/"), "New releases");

        h.append("<tr><td class=\"px\" style=\"padding:0 24px 8px;\">")
         .append("<h1 style=\"margin:0 0 6px;").append(font(26, 800, INK)).append("line-height:32px;\">New music from artists you follow</h1>")
         .append("</td></tr>");

        int shown = 0;
        for (Item it : items) {
            if (shown >= MAX_ITEMS) break;
            row(h, app, media, it);
            shown++;
        }
        if (items.size() > shown) {
            int more = items.size() - shown;
            h.append("<tr><td class=\"px\" style=\"padding:6px 24px 0;").append(font(14, 600, ACCENT_INK)).append("\">")
             .append("<a href=\"").append(attr(link(app, "/messages"))).append("\" style=\"color:").append(ACCENT_INK).append(";\">")
             .append("And ").append(more).append(" more in Unis</a></td></tr>");
        }

        // Footer
        h.append("<tr><td class=\"px\" align=\"center\" style=\"padding:26px 24px 0;\">")
         .append("<p style=\"margin:0 0 10px;").append(font(12, 400, INK_3)).append("line-height:18px;\">")
         .append("You follow these artists on Unis. You get at most one of these a day, only when there’s something new.</p>")
         .append("<p style=\"margin:0 0 10px;").append(font(12, 600, INK_3)).append("\">")
         .append("<a href=\"").append(attr(manageUrl)).append("\" style=\"color:").append(INK_2).append(";text-decoration:underline;\">Manage release alerts</a>")
         .append("&nbsp;&nbsp;&middot;&nbsp;&nbsp;")
         .append("<a href=\"").append(attr(unsubscribeUrl)).append("\" style=\"color:").append(INK_2).append(";text-decoration:underline;\">Unsubscribe</a></p>")
         .append("<p style=\"margin:0;").append(font(11, 400, INK_3)).append("line-height:16px;\">Unis Music Corporation");
        if (postalAddress != null && !postalAddress.isBlank()) h.append("<br>").append(esc(postalAddress));
        h.append("</p></td></tr>");

        h.append("</table></td></tr></table></body></html>");

        StringBuilder t = new StringBuilder(1024);
        t.append("New music from artists you follow\n\n");
        int n = 0;
        for (Item it : items) {
            if (n++ >= MAX_ITEMS) break;
            t.append(it.artistName()).append(" ").append(verb(it)).append(": ").append(it.title()).append("\n");
            if (it.note() != null) t.append("“").append(it.note()).append("”\n");
            t.append(itemLink(app, it)).append("\n\n");
        }
        t.append("Manage release alerts: ").append(manageUrl).append("\n");
        t.append("Unsubscribe: ").append(unsubscribeUrl).append("\n");
        t.append("Unis Music Corporation");
        if (postalAddress != null && !postalAddress.isBlank()) t.append(", ").append(postalAddress);
        t.append("\n");

        return new Rendered(subject, h.toString(), t.toString());
    }

    private static void row(StringBuilder h, String app, Function<String, String> media, Item it) {
        String href = itemLink(app, it);
        String cover = it.coverUrl() == null ? null : media.apply(it.coverUrl());
        h.append("<tr><td class=\"px\" style=\"padding:14px 24px 0;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(CARD)
         .append("\" style=\"background:").append(CARD).append(";border:1px solid ").append(LINE).append(";border-radius:14px;\"><tr>")
         .append("<td width=\"96\" style=\"width:96px;padding:12px 0 12px 12px;vertical-align:top;\">")
         .append("<a href=\"").append(attr(href)).append("\" style=\"display:block;width:84px;height:84px;border-radius:8px;");
        if (cover != null) {
            h.append("background:").append(MONO_BG).append(" url('").append(cssUrl(cover)).append("') center/cover no-repeat;");
        } else {
            h.append("background:").append(MONO_BG).append(";");
        }
        h.append("\">&nbsp;</a></td>")
         .append("<td style=\"padding:12px 14px 12px 14px;vertical-align:top;\">")
         .append("<p style=\"margin:0;").append(font(13, 400, INK_2)).append("line-height:18px;\">")
         .append("<b style=\"color:").append(INK).append(";\">").append(esc(it.artistName())).append("</b> ").append(esc(verb(it))).append("</p>")
         .append("<p style=\"margin:2px 0 0;").append(font(18, 800, INK)).append("line-height:24px;\">")
         .append("<a href=\"").append(attr(href)).append("\" style=\"color:").append(INK).append(";\">").append(esc(it.title())).append("</a></p>");
        if (it.note() != null && !it.note().isBlank()) {
            h.append("<p style=\"margin:6px 0 0;").append(font(13, 400, INK_2)).append("line-height:19px;font-style:italic;\">“")
             .append(esc(it.note())).append("”</p>");
        }
        h.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin-top:10px;\"><tr>")
         .append("<td bgcolor=\"").append(ACCENT).append("\" style=\"border-radius:999px;background:").append(ACCENT).append(";\">")
         .append("<a href=\"").append(attr(href)).append("\" style=\"display:inline-block;padding:8px 16px;").append(font(13, 700, "#ffffff"))
         .append("text-decoration:none;\">Play on Unis</a></td></tr></table>")
         .append("</td></tr></table></td></tr>");
    }

    /** "New from Nia Brooks", "New from Nia Brooks and Kay Monroe", "New from Nia Brooks, Kay Monroe and 2 others". */
    static String subject(List<Item> items) {
        Set<String> names = new LinkedHashSet<>();
        for (Item it : items) names.add(it.artistName());
        List<String> list = new ArrayList<>(names);
        if (list.size() == 1) return "New from " + list.get(0);
        if (list.size() == 2) return "New from " + list.get(0) + " and " + list.get(1);
        int others = list.size() - 2;
        return "New from " + list.get(0) + ", " + list.get(1) + " and " + others + (others == 1 ? " other" : " others");
    }

    /** "dropped an EP", "dropped a new song", "dropped 2 new songs", "dropped an album and a new song". */
    public static String verb(Item it) {
        if ("RELEASE".equals(it.kind())) {
            String type = it.releaseType() == null ? "" : it.releaseType();
            String what = switch (type) {
                case "ALBUM" -> "an album";
                case "EP" -> "an EP";
                case "MIXTAPE" -> "a mixtape";
                default -> "a single";
            };
            if (it.songCount() == 1) return "dropped " + what + " and a new song";
            if (it.songCount() > 1) return "dropped " + what + " and " + it.songCount() + " new songs";
            return "dropped " + what;
        }
        return it.songCount() > 1 ? "dropped " + it.songCount() + " new songs" : "dropped a new song";
    }

    private static String itemLink(String app, Item it) {
        UUID id = "RELEASE".equals(it.kind()) ? it.releaseId() : it.songId();
        String path = "RELEASE".equals(it.kind()) ? "/release/" + id : "/song/" + id;
        return link(app, path);
    }

    private static String link(String app, String path) {
        return app + path + (path.contains("?") ? "&" : "?") + UTM;
    }
}
