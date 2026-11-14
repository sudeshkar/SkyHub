package com.skyhub.notification.application;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The designed HTML version of an email (ADR-0050, ADR-0086), derived from its plain text so the two can never say different things.
 *
 * <p>Every email gets the same frame: a navy header with the SkyHub logo and name, the subject as the heading, and a footer. Inside,
 * the shapes the plain text already has become layout:
 * <ul>
 *   <li>a paragraph whose every line is {@code Label: value} becomes a table of facts;</li>
 *   <li>indented lines, under an optional {@code Heading:} line, become a card with one row per line, and a row that is a route
 *   ({@code CMB 07:30 → DXB 10:50}) is drawn large, airport to airport (ADR-0087);</li>
 *   <li>a paragraph that is only a web address becomes a button, with the address shown under it to copy;</li>
 *   <li>anything else is a paragraph that flows (the text's line breaks only wrap it), with its web addresses as links.</li>
 * </ul>
 *
 * <p>Everything is escaped first, so a name can never become markup, and only {@code http} and {@code https} addresses become links.
 * Nothing is loaded from the internet: the logo travels inside the message ({@link #LOGO_CONTENT_ID}), the styles are inline, and
 * there are no scripts or tracking pixels. The layout uses tables and inline styles because that is what mail programs render.
 */
public final class HtmlBody {

    /** The Content-ID of the logo picture that travels inside the message. */
    public static final String LOGO_CONTENT_ID = "skyhub-logo";

    // The website's "Clear Sky" colours (ADR-0084).
    private static final String INK = "#0b1b33";
    private static final String ACCENT = "#0a72e6";
    private static final String SKY = "#8ad8fc";
    private static final String PAGE = "#eef3f9";
    private static final String CARD = "#f5f8fc";
    private static final String LINE = "#dbe4ef";
    private static final String MUTED = "#55657a";
    private static final String FONT = "font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;";

    /** An address in already-escaped text. It stops at white space; the punctuation that ends a sentence is trimmed after. */
    private static final Pattern WEB_ADDRESS = Pattern.compile("https?://[^\\s<]+");
    private static final String TRAILING_PUNCTUATION = ".,;:!?)";
    /** "Booking reference: ABC234". A short label of words, so a sentence that happens to hold a colon stays a sentence. */
    private static final Pattern FACT = Pattern.compile("([A-Z][A-Za-z -]{0,29}): (\\S.*)");
    /** "CMB 07:30 → DXB 10:50 (+1)": codes and local times; the landing time, and its later day, only when known (ADR-0087). */
    private static final Pattern ROUTE =
            Pattern.compile("([A-Z]{3}) (\\d{2}:\\d{2}) → ([A-Z]{3})(?: (\\d{2}:\\d{2}(?: \\(\\+\\d+\\))?))?");
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\R[ \\t]*\\R");
    private static final int PREVIEW_LENGTH = 140;

    private HtmlBody() {
    }

    public static String from(String subject, String text) {
        List<String> paragraphs = paragraphs(text);
        StringBuilder blocks = new StringBuilder();
        for (String paragraph : paragraphs) {
            blocks.append(block(paragraph));
        }
        String preview = paragraphs.isEmpty() ? "" : paragraphs.getFirst().strip().replaceAll("\\s+", " ");
        if (preview.length() > PREVIEW_LENGTH) {
            preview = preview.substring(0, PREVIEW_LENGTH - 1) + "…";
        }
        return frame(escape(subject), escape(preview), blocks.toString());
    }

    /** Blank-line separated paragraphs, with their leading indentation kept: it is what marks a list. */
    private static List<String> paragraphs(String text) {
        List<String> found = new ArrayList<>();
        for (String paragraph : PARAGRAPH_BREAK.split(text)) {
            String trimmed = paragraph.replaceAll("^(\\s*\\R)+", "").stripTrailing();
            if (!trimmed.isBlank()) {
                found.add(trimmed);
            }
        }
        return found;
    }

    private static String block(String paragraph) {
        String[] lines = paragraph.split("\\R");
        String alone = paragraph.strip();
        if (lines.length == 1 && alone.matches("https?://\\S+")) {
            return button(escape(alone));
        }
        if (allFacts(lines)) {
            return facts(lines);
        }
        boolean titled = lines.length > 1 && lines[0].strip().endsWith(":") && !lines[0].startsWith(" ") && allIndented(lines, 1);
        if (titled) {
            String title = lines[0].strip();
            return card(title.substring(0, title.length() - 1), lines, 1);
        }
        if (allIndented(lines, 0)) {
            return card(null, lines, 0);
        }
        // The plain text is wrapped by hand to stay readable as text; in HTML the paragraph flows and the mail program wraps it.
        return "<p style=\"margin:0 0 16px;\">" + linkify(escape(alone.replaceAll("\\s*\\R\\s*", " "))) + "</p>\n";
    }

    private static boolean allFacts(String[] lines) {
        for (String line : lines) {
            if (!FACT.matcher(line.strip()).matches() || line.startsWith(" ")) {
                return false;
            }
        }
        return true;
    }

    private static boolean allIndented(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            if (!lines[i].startsWith("  ") || lines[i].isBlank()) {
                return false;
            }
        }
        return from < lines.length;
    }

    private static String facts(String[] lines) {
        StringBuilder rows = new StringBuilder();
        for (String line : lines) {
            Matcher fact = FACT.matcher(line.strip());
            fact.matches();
            rows.append("<tr><td style=\"padding:6px 12px 6px 0;color:").append(MUTED).append(";white-space:nowrap;vertical-align:top;\">")
                    .append(escape(fact.group(1))).append("</td>\n<td style=\"padding:6px 0;font-weight:600;color:").append(INK)
                    .append(";\">").append(linkify(escape(fact.group(2)))).append("</td></tr>\n");
        }
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 0 16px;border-collapse:collapse;\">\n"
                + rows + "</table>\n";
    }

    private static String card(String title, String[] lines, int from) {
        StringBuilder out = new StringBuilder();
        if (title != null) {
            out.append("<div style=\"margin:0 0 8px;font-size:13px;font-weight:700;letter-spacing:.04em;text-transform:uppercase;color:")
                    .append(MUTED).append(";\">").append(escape(title)).append("</div>\n");
        }
        out.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 0 20px;background:")
                .append(CARD).append(";border:1px solid ").append(LINE).append(";border-radius:10px;border-collapse:separate;\">\n");
        for (int i = from; i < lines.length; i++) {
            String border = i == lines.length - 1 ? "" : "border-bottom:1px solid " + LINE + ";";
            Matcher route = ROUTE.matcher(lines[i].strip());
            if (route.matches()) {
                out.append("<tr><td style=\"padding:16px;").append(border).append("\">").append(route(route)).append("</td></tr>\n");
                continue;
            }
            out.append("<tr><td style=\"padding:12px 16px;").append(border).append("color:").append(INK).append(";\">")
                    .append(linkify(escape(lines[i].strip()))).append("</td></tr>\n");
        }
        return out.append("</table>\n").toString();
    }

    /** Airport to airport, large. The pattern lets through only codes, times and "(+1)", and each part is escaped anyway. */
    private static String route(Matcher route) {
        String arrives = route.group(4) == null ? "&nbsp;" : escape(route.group(4));
        String code = "font-size:28px;line-height:1.1;font-weight:800;letter-spacing:.02em;color:" + INK + ";";
        String time = "font-size:15px;font-weight:600;color:" + MUTED + ";";
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
                + "<td width=\"40%\" style=\"vertical-align:top;\"><div style=\"" + code + "\">" + escape(route.group(1)) + "</div>"
                + "<div style=\"" + time + "\">" + escape(route.group(2)) + "</div></td>"
                + "<td width=\"20%\" align=\"center\" style=\"vertical-align:middle;color:" + ACCENT + ";font-size:22px;\">&#9992;</td>"
                + "<td width=\"40%\" align=\"right\" style=\"vertical-align:top;\"><div style=\"" + code + "\">" + escape(route.group(3))
                + "</div><div style=\"" + time + "\">" + arrives + "</div></td></tr></table>";
    }

    private static String button(String address) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:4px 0 8px;\"><tr>"
                + "<td style=\"border-radius:999px;background:" + ACCENT + ";\">"
                + "<a href=\"" + address + "\" style=\"display:inline-block;padding:12px 28px;color:#ffffff;font-weight:700;"
                + "text-decoration:none;border-radius:999px;\">Open the link</a></td></tr></table>\n"
                + "<p style=\"margin:0 0 16px;font-size:12px;color:" + MUTED + ";word-break:break-all;\">"
                + "Or copy this address into your browser:<br>\n<span>" + address + "</span></p>\n";
    }

    private static String frame(String subject, String preview, String blocks) {
        return "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<meta name=\"color-scheme\" content=\"light\">\n"
                + "<title>" + subject + "</title>\n</head>\n"
                + "<body style=\"margin:0;padding:0;background:" + PAGE + ";\">\n"
                + "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">" + preview + "</div>\n"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:" + PAGE + ";\">"
                + "<tr><td align=\"center\" style=\"padding:24px 12px;\">\n"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;"
                + "background:#ffffff;border-radius:14px;border-collapse:separate;overflow:hidden;" + FONT
                + "font-size:16px;line-height:1.5;color:" + INK + ";\">\n"
                + "<tr><td style=\"background:" + INK + ";padding:18px 28px;\">"
                + "<img src=\"cid:" + LOGO_CONTENT_ID + "\" width=\"36\" height=\"36\" alt=\"\" "
                + "style=\"vertical-align:middle;border:0;background:#ffffff;border-radius:50%;\">"
                + "<span style=\"vertical-align:middle;margin-left:10px;color:#ffffff;font-size:22px;font-weight:700;"
                + "letter-spacing:.01em;\">SkyHub</span></td></tr>\n"
                + "<tr><td style=\"height:4px;line-height:4px;font-size:0;background:" + ACCENT + ";"
                + "background-image:linear-gradient(90deg," + ACCENT + "," + SKY + ");\">&nbsp;</td></tr>\n"
                + "<tr><td style=\"padding:28px 28px 12px;\">\n"
                + "<h1 style=\"margin:0 0 20px;font-size:22px;line-height:1.3;color:" + INK + ";\">" + subject + "</h1>\n"
                + blocks
                + "</td></tr>\n"
                + "<tr><td style=\"padding:18px 28px;background:" + CARD + ";border-top:1px solid " + LINE + ";font-size:12px;"
                + "line-height:1.5;color:" + MUTED + ";\">"
                + "SkyHub connects Dubai International (DXB) and Al Maktoum (DWC).<br>\n"
                + "SkyHub is a portfolio project, not a real airline. This email loads nothing from the internet: "
                + "no pictures from a server and no tracking.</td></tr>\n"
                + "</table>\n</td></tr></table>\n</body>\n</html>\n";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String linkify(String escaped) {
        Matcher found = WEB_ADDRESS.matcher(escaped);
        StringBuilder out = new StringBuilder();
        while (found.find()) {
            String address = found.group();
            int end = address.length();
            while (end > 0 && TRAILING_PUNCTUATION.indexOf(address.charAt(end - 1)) >= 0) {
                end--;
            }
            String link = address.substring(0, end);
            String rest = address.substring(end);
            found.appendReplacement(out, Matcher.quoteReplacement(
                    "<a href=\"" + link + "\" style=\"color:" + ACCENT + ";\">" + link + "</a>" + rest));
        }
        found.appendTail(out);
        return out.toString();
    }
}
