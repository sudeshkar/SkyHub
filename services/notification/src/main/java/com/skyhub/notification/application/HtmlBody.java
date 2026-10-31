package com.skyhub.notification.application;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTML version of an email, derived from its plain text (ADR-0050). Everything is escaped first, so a name can never
 * become markup; only {@code http} and {@code https} addresses become links; there is no remote content of any kind.
 */
public final class HtmlBody {

    /** An address in already-escaped text. It stops at white space; the punctuation that ends a sentence is trimmed after. */
    private static final Pattern WEB_ADDRESS = Pattern.compile("https?://[^\\s<]+");
    private static final String TRAILING_PUNCTUATION = ".,;:!?)";

    private HtmlBody() {
    }

    public static String from(String text) {
        StringBuilder paragraphs = new StringBuilder();
        for (String paragraph : text.strip().split("\\R\\s*\\R")) {
            String lines = escape(paragraph.strip()).replaceAll("\\R", "<br>\n");
            paragraphs.append("<p>").append(linkify(lines)).append("</p>\n");
        }
        return "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n</head>\n"
                + "<body style=\"font-family: Arial, Helvetica, sans-serif; font-size: 16px; line-height: 1.5; color: #1a1a1a;"
                + " max-width: 36em; margin: 0 auto; padding: 16px;\">\n"
                + paragraphs + "</body>\n</html>\n";
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
            found.appendReplacement(out, Matcher.quoteReplacement("<a href=\"" + link + "\">" + link + "</a>" + rest));
        }
        found.appendTail(out);
        return out.toString();
    }
}
