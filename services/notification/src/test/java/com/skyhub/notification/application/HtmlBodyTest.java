package com.skyhub.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class HtmlBodyTest {

    private static final String SUBJECT = "Your SkyHub booking ABC234 is confirmed";

    private static String html(String text) {
        return HtmlBody.from(SUBJECT, text);
    }

    // ---- the frame every email shares (ADR-0086) -----------------------------------------------------------------------------

    @Test
    void should_be_a_complete_document_with_the_subject_as_its_title_and_heading() {
        String html = html("Your booking is confirmed.\n");

        assertThat(html).startsWith("<!doctype html>").contains("<meta charset=\"utf-8\">").contains("</html>")
                .contains("<title>" + SUBJECT + "</title>")
                .containsPattern("<h1[^>]*>" + SUBJECT + "</h1>");
    }

    @Test
    void should_carry_the_skyhub_name_and_logo_in_the_header_with_the_logo_inside_the_email_not_on_a_server() {
        String html = html("Hello.\n");

        assertThat(html).contains("src=\"cid:" + HtmlBody.LOGO_CONTENT_ID + "\"").contains(">SkyHub<");
    }

    @Test
    void should_load_nothing_from_the_internet_no_remote_picture_no_script_no_style_sheet() {
        String html = html("Open https://skyhub.example/account/set-password?token=abc to continue.\n");

        Matcher sources = Pattern.compile("src=\"([^\"]*)\"").matcher(html);
        while (sources.find()) {
            assertThat(sources.group(1)).startsWith("cid:");
        }
        assertThat(html).doesNotContain("<script").doesNotContain("<link").doesNotContain("@import").doesNotContain("url(");
    }

    @Test
    void should_show_the_first_paragraph_as_the_inbox_preview_line() {
        String html = html("Your booking is confirmed.\n\nBooking reference: ABC234\n");

        assertThat(html).containsPattern("<div style=\"display:none[^\"]*\">Your booking is confirmed\\.</div>");
    }

    @Test
    void should_say_in_the_footer_that_skyhub_is_not_a_real_airline() {
        assertThat(html("Hello.\n")).contains("not a real airline");
    }

    @Test
    void should_escape_the_subject_too() {
        String html = HtmlBody.from("Flight <b>SK1</b> & \"you\"", "Hello.\n");

        assertThat(html).contains("<title>Flight &lt;b&gt;SK1&lt;/b&gt; &amp; &quot;you&quot;</title>").doesNotContain("<b>SK1");
    }

    // ---- what the text's own shape becomes ---------------------------------------------------------------------------------

    @Test
    void should_let_an_ordinary_paragraph_flow_because_its_line_breaks_only_wrap_the_plain_text() {
        String html = html("To see your booking, open Manage booking and enter this reference\nwith the last name of any passenger.\n");

        assertThat(html).contains(">To see your booking, open Manage booking and enter this reference with the last name of any passenger.</p>")
                .doesNotContain("reference<br>");
    }

    @Test
    void should_lay_out_label_and_value_lines_as_a_table_of_facts() {
        String html = html("Booking reference: ABC234\nTotal paid: 200.00 USD\n");

        assertThat(html).containsPattern("<td[^>]*>Booking reference</td>\\s*<td[^>]*>ABC234</td>")
                .containsPattern("<td[^>]*>Total paid</td>\\s*<td[^>]*>200\\.00 USD</td>");
    }

    @Test
    void should_lay_out_a_heading_followed_by_indented_lines_as_a_titled_card_with_one_row_per_line() {
        String html = html("Flights (all times UTC on the booking page):\n  SK001 on 2028-01-10\n  SK002 on 2028-01-11\n");

        assertThat(html).containsPattern(">Flights \\(all times UTC on the booking page\\)</")
                .containsPattern("<td[^>]*>SK001 on 2028-01-10</td>").containsPattern("<td[^>]*>SK002 on 2028-01-11</td>");
    }

    @Test
    void should_lay_out_indented_lines_with_no_heading_as_a_card_too() {
        String html = html("You are checked in.\n\n  Priya Perera: seat 14A, boarding number 3\n  Anil Perera: seat 14B, boarding number 4\n");

        assertThat(html).containsPattern("<td[^>]*>Priya Perera: seat 14A, boarding number 3</td>")
                .containsPattern("<td[^>]*>Anil Perera: seat 14B, boarding number 4</td>");
    }

    @Test
    void should_make_a_link_that_stands_alone_a_button_and_still_show_the_address_to_copy() {
        String link = "https://skyhub.example/account/set-password?token=a-b_c%2B&x=1";

        String html = html("To choose a password, open this link:\n\n" + link + "\n\nThe link works once.\n");

        String escaped = "https://skyhub.example/account/set-password?token=a-b_c%2B&amp;x=1";
        assertThat(html).containsPattern("<a href=\"" + Pattern.quote(escaped) + "\"[^>]*>Open the link</a>")
                .contains(">" + escaped + "<");
    }

    @Test
    void should_turn_a_web_link_inside_a_sentence_into_an_anchor_with_the_ampersands_escaped() {
        String html = html("Open https://skyhub.example/account/confirm?token=a-b_c%2B&x=1 to continue.\n");

        assertThat(html).containsPattern("<a href=\"https://skyhub\\.example/account/confirm\\?token=a-b_c%2B&amp;x=1\"[^>]*>"
                + "https://skyhub\\.example/account/confirm\\?token=a-b_c%2B&amp;x=1</a> to continue\\.");
    }

    @Test
    void should_not_swallow_the_full_stop_that_ends_a_sentence_into_the_link() {
        String html = html("Go to https://skyhub.example/x.\n");

        assertThat(html).containsPattern("<a href=\"https://skyhub\\.example/x\"[^>]*>https://skyhub\\.example/x</a>\\.");
    }

    @Test
    void should_not_link_anything_but_web_addresses() {
        String html = html("javascript:alert(1) and ftp://host/file and mailto:a@b.c\n");

        assertThat(html).doesNotContain("href=\"javascript").doesNotContain("href=\"ftp").doesNotContain("href=\"mailto");
    }

    @Test
    void should_escape_everything_a_name_could_use_to_inject_markup_wherever_it_lands() {
        String name = "<script>alert(1)</script> \"O'Brien\" & Co";

        String html = html("Hello " + name + ".\n\nPassengers:\n  " + name + "\n\nName: " + name + "\n");

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;").contains("&amp; Co")
                .contains("&quot;O&#39;Brien&quot;");
    }

    @Test
    void should_not_mistake_a_sentence_with_a_colon_for_a_fact() {
        String html = html("You cancelled less than 24 hours before the first flight, so this booking is not refundable: nothing will be refunded.\n");

        assertThat(html).containsPattern("<p[^>]*>You cancelled less than 24 hours");
    }
    @Test
    void should_draw_a_route_line_in_a_card_as_a_large_route_with_its_times() {
        String html = html("SK002 · Fri 20 Nov 2026:\n  LHR 14:00 → DXB 00:50 (+1)\n  Priya Perera: seat 24A\n");

        assertThat(html).containsPattern(">LHR</").containsPattern(">14:00<").containsPattern(">DXB</")
                .containsPattern(">00:50 \\(\\+1\\)<").contains("Priya Perera: seat 24A")
                .doesNotContainPattern("<td[^>]*>LHR 14:00 → DXB");   // drawn, not left as a plain row (the preview line keeps the text)
    }

    @Test
    void should_draw_a_route_with_no_landing_time() {
        String html = html("SK501:\n  CMB 07:30 → DXB\n");

        assertThat(html).containsPattern(">CMB</").containsPattern(">07:30<").containsPattern(">DXB</");
    }
}
