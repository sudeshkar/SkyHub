package com.skyhub.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlBodyTest {

    @Test
    void should_turn_blank_line_separated_paragraphs_into_html_paragraphs() {
        String html = HtmlBody.from("Your booking is confirmed.\n\nBooking reference: ABC234\n");

        assertThat(html).contains("<p>Your booking is confirmed.</p>").contains("<p>Booking reference: ABC234</p>");
    }

    @Test
    void should_keep_single_line_breaks_inside_a_paragraph() {
        String html = HtmlBody.from("Flights:\n  SK001 on 2028-01-10\n  SK002 on 2028-01-11\n");

        assertThat(html).contains("Flights:<br>").contains("SK001 on 2028-01-10<br>");
    }

    @Test
    void should_escape_everything_a_name_could_use_to_inject_markup() {
        String html = HtmlBody.from("Passengers:\n  <script>alert(1)</script> \"O'Brien\" & Co\n");

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;").contains("&amp; Co")
                .contains("&quot;O&#39;Brien&quot;");
    }

    @Test
    void should_turn_a_web_link_into_an_anchor_with_the_ampersands_escaped() {
        String html = HtmlBody.from("Open https://skyhub.example/account/confirm?token=a-b_c%2B&x=1 to continue.\n");

        assertThat(html).contains("<a href=\"https://skyhub.example/account/confirm?token=a-b_c%2B&amp;x=1\">"
                + "https://skyhub.example/account/confirm?token=a-b_c%2B&amp;x=1</a> to continue.");
    }

    @Test
    void should_not_swallow_the_full_stop_that_ends_a_sentence_into_the_link() {
        String html = HtmlBody.from("Go to https://skyhub.example/x.\n");

        assertThat(html).contains("<a href=\"https://skyhub.example/x\">https://skyhub.example/x</a>.");
    }

    @Test
    void should_not_link_anything_but_web_addresses() {
        String html = HtmlBody.from("javascript:alert(1) and ftp://host/file and mailto:a@b.c\n");

        assertThat(html).doesNotContain("<a ");
    }

    @Test
    void should_be_a_complete_document_with_no_remote_content() {
        String html = HtmlBody.from("Hello.\n");

        assertThat(html).startsWith("<!doctype html>").contains("<meta charset=\"utf-8\">").contains("</html>")
                .doesNotContain("<img").doesNotContain("<script").doesNotContain("src=");
    }
}
