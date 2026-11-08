package com.unis.service.mail;

/**
 * What every Unis email shares: the palette, the type, and the header with
 * the Unis logo.
 *
 * <p><b>The logo</b> is a PNG, not SVG (Gmail drops SVG), served by the web
 * app at {@code <app>/email/unis-wordmark.png}: the same wordmark as the
 * site, the app and the postcard, drawn at 4x so it stays sharp on phones.
 * The link comes from {@code unis.app-base-url} (UNIS_APP_BASE_URL in
 * Railway), so that has to be the live site, not localhost. When a mail app
 * blocks images, the alt text shows "Unis" in the same white as the old text
 * wordmark.
 *
 * <p>Used by the Sunday email (WeeklyDigestRenderer) and the referral email
 * (ReferralNoticeRenderer). Pure Java, no Spring.
 */
public final class EmailBrand {

    // ── Palette (dark, declared dark so mail apps don't invert it) ───────────
    public static final String BG = "#07080c";
    public static final String CARD = "#10131b";
    public static final String CARD_2 = "#171b26";
    public static final String LINE = "#242a3a";
    public static final String INK = "#f4f5f8";
    public static final String INK_2 = "#b6bbc8";
    public static final String INK_3 = "#7d8396";
    public static final String ACCENT = "#1E50EB";      // the Unis blue
    public static final String ACCENT_INK = "#8fa8ff";  // the same blue, legible as text on dark
    public static final String MONO_BG = "#14204d";     // no-photo tiles, tinted pills
    public static final String MONO_INK = "#7f95e8";
    public static final String GOLD = "#f5c242";
    public static final String UP = "#34d399";

    public static final String FONT =
        "'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif";

    /** Where the web app serves the logo (unis-sample/public/email/unis-wordmark.png). */
    public static final String LOGO_PATH = "/email/unis-wordmark.png";
    /** Shown size. The file is 408 x 96, so 3.4x for sharp phone screens. */
    public static final int LOGO_WIDTH = 119;
    public static final int LOGO_HEIGHT = 28;

    private EmailBrand() {}

    public static String logoUrl(String appBaseUrl) {
        return trimSlash(appBaseUrl) + LOGO_PATH;
    }

    /**
     * The header row: the logo on the left (linking home), a small label on
     * the right ("Nov 2 – 8", "New in your network"). Appends one table row
     * to the 600px column.
     */
    public static void header(StringBuilder h, String appBaseUrl, String homeHref, String rightLabel) {
        h.append("<tr><td class=\"px\" style=\"padding:4px 24px 26px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
         .append("<td align=\"left\" style=\"vertical-align:middle;\">")
         .append("<a href=\"").append(attr(homeHref)).append("\" style=\"text-decoration:none;display:inline-block;\">")
         .append("<img src=\"").append(attr(logoUrl(appBaseUrl))).append("\" width=\"").append(LOGO_WIDTH)
         .append("\" height=\"").append(LOGO_HEIGHT).append("\" alt=\"Unis\" style=\"display:block;width:").append(LOGO_WIDTH)
         .append("px;height:").append(LOGO_HEIGHT).append("px;border:0;outline:none;text-decoration:none;")
         // Styles for the alt text when images are off.
         .append(font(22, 800, INK)).append("line-height:").append(LOGO_HEIGHT).append("px;letter-spacing:-0.03em;\">")
         .append("</a></td>");
        if (rightLabel != null && !rightLabel.isBlank()) {
            h.append("<td align=\"right\" style=\"vertical-align:middle;").append(font(12, 600, INK_3)).append("\">")
             .append(esc(rightLabel)).append("</td>");
        }
        h.append("</tr></table></td></tr>");
    }

    public static String font(int size, int weight, String color) {
        return "font-family:" + FONT + ";font-size:" + size + "px;font-weight:" + weight + ";color:" + color + ";";
    }

    /** HTML text escaping. */
    public static String esc(String s) {
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
    public static String attr(String s) {
        return esc(s == null ? "" : s.replace(" ", "%20"));
    }

    /** A URL that is safe inside url('...') in an inline style attribute. */
    public static String cssUrl(String s) {
        return s.replace(" ", "%20").replace("'", "%27").replace("(", "%28").replace(")", "%29")
                .replace("\"", "%22").replace("&", "&amp;");
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.replaceAll("/+$", "");
    }
}
