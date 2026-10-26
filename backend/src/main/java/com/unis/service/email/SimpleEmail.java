package com.unis.service.email;

import java.util.List;

/**
 * A plain, on-brand email: a heading, a few paragraphs and up to three
 * buttons, in the same dark palette as the Sunday email. Used by the street
 * team and winner emails. Table layout and inline styles only, so it holds up
 * in Gmail and Outlook. Every piece of text is escaped here; callers pass
 * plain text, never HTML.
 */
public final class SimpleEmail {

    private static final String BG = "#07080c";
    private static final String CARD = "#10131b";
    private static final String LINE = "#242a3a";
    private static final String INK = "#f4f5f8";
    private static final String MUTED = "#a3a9b8";
    private static final String ACCENT = "#1E50EB";

    private SimpleEmail() {}

    /** A button: label and absolute link. primary = filled blue, otherwise outlined. */
    public record Button(String label, String url, boolean primary) {}

    /** A rendered email with an HTML and a plain-text part. */
    public record Rendered(String subject, String html, String text) {}

    public static Rendered render(String subject, String heading, List<String> paragraphs,
                                  List<Button> buttons, String footer) {
        StringBuilder h = new StringBuilder(4096);
        h.append("<!doctype html><html><head><meta charset=\"utf-8\">")
         .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
         .append("<title>").append(esc(subject)).append("</title></head>")
         .append("<body style=\"margin:0;padding:0;background:").append(BG).append(";\" bgcolor=\"").append(BG).append("\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"").append(BG)
         .append("\"><tr><td align=\"center\" style=\"padding:24px 12px 40px;\">")
         .append("<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:560px;max-width:100%;\">")
         .append("<tr><td style=\"padding:0 4px 16px;font-family:Inter,Arial,sans-serif;font-size:20px;font-weight:700;letter-spacing:2px;color:")
         .append(INK).append(";\">UNIS</td></tr>")
         .append("<tr><td bgcolor=\"").append(CARD).append("\" style=\"background:").append(CARD)
         .append(";border:1px solid ").append(LINE).append(";border-radius:18px;padding:28px 26px;font-family:Inter,Arial,sans-serif;\">")
         .append("<h1 style=\"margin:0 0 14px;font-size:24px;line-height:1.25;color:").append(INK).append(";\">")
         .append(esc(heading)).append("</h1>");
        for (String p : paragraphs) {
            h.append("<p style=\"margin:0 0 14px;font-size:16px;line-height:1.55;color:").append(INK).append(";\">")
             .append(esc(p)).append("</p>");
        }
        if (buttons != null && !buttons.isEmpty()) {
            h.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin-top:8px;\"><tr>");
            for (Button b : buttons) {
                String bg = b.primary() ? ACCENT : CARD;
                String border = b.primary() ? ACCENT : LINE;
                h.append("<td style=\"padding:0 10px 10px 0;\"><a href=\"").append(esc(b.url()))
                 .append("\" style=\"display:inline-block;padding:13px 22px;border-radius:999px;background:").append(bg)
                 .append(";border:1px solid ").append(border).append(";color:#ffffff;font-size:15px;font-weight:600;text-decoration:none;\">")
                 .append(esc(b.label())).append("</a></td>");
            }
            h.append("</tr></table>");
        }
        h.append("</td></tr>");
        if (footer != null && !footer.isBlank()) {
            h.append("<tr><td style=\"padding:16px 6px 0;font-family:Inter,Arial,sans-serif;font-size:12.5px;line-height:1.5;color:")
             .append(MUTED).append(";\">").append(esc(footer)).append("</td></tr>");
        }
        h.append("</table></td></tr></table></body></html>");

        StringBuilder t = new StringBuilder(1024);
        t.append(heading).append("\n\n");
        for (String p : paragraphs) t.append(p).append("\n\n");
        if (buttons != null) for (Button b : buttons) t.append(b.label()).append(": ").append(b.url()).append("\n");
        if (footer != null && !footer.isBlank()) t.append("\n").append(footer).append("\n");
        return new Rendered(subject, h.toString(), t.toString());
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
}
