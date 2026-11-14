package com.skyhub.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.skyhub.notification.application.Itinerary;
import com.skyhub.notification.domain.BookingConfirmedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PdfBoxETicketsTest {

    private final PdfBoxETickets tickets = new PdfBoxETickets();

    private static Itinerary trip(String firstName, String lastName) {
        var sk501 = new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2), "CMB", "DXB",
                Instant.parse("2026-11-02T02:00:00Z"), Instant.parse("2026-11-02T06:50:00Z"));
        var sk002 = new BookingConfirmedEvent.Segment("SK002", LocalDate.of(2026, 11, 20), "LHR", "DXB",
                Instant.parse("2026-11-20T14:00:00Z"), Instant.parse("2026-11-20T20:50:00Z"));
        return Itinerary.of(new BookingConfirmedEvent(UUID.randomUUID(), "booking.confirmed", 1, Instant.parse("2026-10-30T10:00:00Z"),
                "ABC234", "priya@example.com", List.of(
                        new BookingConfirmedEvent.Passenger(firstName, lastName, List.of("11D", "24A")),
                        new BookingConfirmedEvent.Passenger("Anil", "Perera", List.of("11E", "24B"))),
                List.of(sk501, sk002), new BigDecimal("1240.00"), "USD")).orElseThrow();
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void should_draw_a_one_page_pdf_with_the_reference_passengers_flights_seats_and_total() throws Exception {
        byte[] pdf = tickets.pdf(trip("Priya", "Perera"));

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = textOf(pdf);
        assertThat(text).contains("ABC234").contains("Priya Perera").contains("Anil Perera")
                .contains("SK501").contains("CMB").contains("07:30").contains("DXB").contains("10:50")
                .contains("Colombo").contains("Bandaranaike International")
                .contains("SK002").contains("00:50 (+1)")
                .contains("11D").contains("24B")
                .contains("1240.00 USD");
    }

    @Test
    void should_say_plainly_that_it_is_a_receipt_from_a_portfolio_project_and_not_valid_for_travel() throws Exception {
        String text = textOf(tickets.pdf(trip("Priya", "Perera")));

        assertThat(text).contains("Itinerary receipt").contains("not a real airline").contains("not valid for travel");
    }

    @Test
    void should_still_draw_a_name_the_standard_fonts_cannot_print_marking_only_the_letters_it_cannot() throws Exception {
        String text = textOf(tickets.pdf(trip("José", "සුදේශ්")));

        assertThat(text).contains("José ?");
    }
}
