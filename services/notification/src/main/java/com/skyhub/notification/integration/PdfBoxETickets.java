package com.skyhub.notification.integration;

import com.skyhub.notification.application.ETickets;
import com.skyhub.notification.application.Itinerary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

/**
 * Adapter: PDFBox draws the e-ticket (ADR-0087), an A4 itinerary receipt in the SkyHub colours: a navy header with the logo, the
 * booking reference and total, the passengers, and a card per flight with the route large, local times, airports and seats. A long
 * booking continues on a further page.
 *
 * <p>It uses the standard PDF fonts, so nothing has to be embedded; a letter they cannot print (Sinhala, Tamil, Arabic script) is
 * shown as {@code ?}. Nothing is stored, logged or sent from here: the itinerary goes in, the PDF comes out.
 */
@Component
public class PdfBoxETickets implements ETickets {

    private static final float[] INK = rgb(0x0b1b33);
    private static final float[] ACCENT = rgb(0x0a72e6);
    private static final float[] SKY = rgb(0x8ad8fc);
    private static final float[] CARD = rgb(0xf5f8fc);
    private static final float[] LINE = rgb(0xdbe4ef);
    private static final float[] MUTED = rgb(0x55657a);
    private static final float[] WHITE = rgb(0xffffff);

    private static final float WIDTH = PDRectangle.A4.getWidth();
    private static final float HEIGHT = PDRectangle.A4.getHeight();
    private static final float MARGIN = 40;
    private static final float BOTTOM = 96;   // room for the notes at the foot of the last page

    private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    @Override
    public byte[] pdf(Itinerary trip) {
        try (PDDocument document = new PDDocument()) {
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle("SkyHub e-ticket " + trip.locator());
            info.setCreator("SkyHub");
            PDImageXObject logo = logo(document);
            Page page = new Page(document, logo, trip.locator());

            page.label(MARGIN, page.y, "BOOKING REFERENCE");
            page.label(WIDTH - MARGIN - 160, page.y, "TOTAL PAID");
            page.y -= 28;
            page.text(bold, 26, INK, MARGIN, page.y, trip.locator());
            page.text(bold, 16, INK, WIDTH - MARGIN - 160, page.y + 4, trip.total());
            page.y -= 34;
            page.label(MARGIN, page.y, "PASSENGERS");
            page.y -= 16;
            page.text(regular, 12, INK, MARGIN, page.y, String.join(",  ", trip.passengers()));
            page.y -= 30;

            for (Itinerary.Leg leg : trip.legs()) {
                float height = 112 + 16 * leg.seats().size();
                page = page.roomFor(height);
                flight(page, leg, height);
                page.y -= height + 16;
            }
            page.notes();
            page.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("The e-ticket could not be drawn", e);
        }
    }

    private void flight(Page page, Itinerary.Leg leg, float height) throws IOException {
        float top = page.y;
        float left = MARGIN;
        float right = WIDTH - MARGIN;
        page.box(left, top - height, right - left, height, CARD, LINE);
        page.text(bold, 12, INK, left + 16, top - 22, leg.heading());

        float codes = top - 58;
        page.text(bold, 28, INK, left + 16, codes, leg.from().code());
        page.text(bold, 28, INK, right - 16 - page.width(bold, 28, leg.to().code()), codes, leg.to().code());
        page.text(bold, 13, MUTED, left + 16, codes - 18, leg.departs());
        String arrives = leg.arrives() == null ? "" : leg.arrives() + leg.landing();
        page.text(bold, 13, MUTED, right - 16 - page.width(bold, 13, arrives), codes - 18, arrives);
        page.arrow(left + 110, right - 110, codes + 9);

        float names = codes - 36;
        page.text(regular, 9, MUTED, left + 16, names, page.fit(leg.from().described(), regular, 9, 220));
        String to = page.fit(leg.to().described(), regular, 9, 220);
        page.text(regular, 9, MUTED, right - 16 - page.width(regular, 9, to), names, to);

        float seats = names - 22;
        page.rule(left + 16, right - 16, seats + 12);
        for (Itinerary.Seat seat : leg.seats()) {
            page.text(regular, 11, INK, left + 16, seats, seat.name());
            String place = "Seat " + seat.seat();
            page.text(bold, 11, INK, right - 16 - page.width(bold, 11, place), seats, place);
            seats -= 16;
        }
    }

    private static PDImageXObject logo(PDDocument document) throws IOException {
        try (InputStream in = PdfBoxETickets.class.getResourceAsStream("/email/skyhub-logo.png")) {
            if (in == null) {
                throw new IOException("The logo is missing from the classpath");
            }
            return PDImageXObject.createFromByteArray(document, in.readAllBytes(), "skyhub-logo");
        }
    }

    private static float[] rgb(int hex) {
        return new float[] {((hex >> 16) & 0xff) / 255f, ((hex >> 8) & 0xff) / 255f, (hex & 0xff) / 255f};
    }

    /** One page being drawn, and where the next thing goes ({@code y}, from the bottom as PDF counts). */
    private final class Page {

        private final PDDocument document;
        private final PDImageXObject logo;
        private final String locator;
        private final PDPageContentStream content;
        private float y;

        Page(PDDocument document, PDImageXObject logo, String locator) throws IOException {
            this.document = document;
            this.logo = logo;
            this.locator = locator;
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            this.content = new PDPageContentStream(document, page);
            header();
            this.y = HEIGHT - 128;
        }

        /** This page if there is room for {@code height} more, otherwise a new one. */
        Page roomFor(float height) throws IOException {
            if (y - height >= BOTTOM) {
                return this;
            }
            close();
            return new Page(document, logo, locator);
        }

        private void header() throws IOException {
            box(0, HEIGHT - 92, WIDTH, 92, INK, null);
            box(0, HEIGHT - 96, WIDTH, 4, ACCENT, null);
            box(WIDTH / 2, HEIGHT - 96, WIDTH / 2, 4, SKY, null);
            circle(MARGIN + 22, HEIGHT - 46, 23, WHITE);
            content.drawImage(logo, MARGIN + 2, HEIGHT - 66, 40, 40);
            text(bold, 24, WHITE, MARGIN + 56, HEIGHT - 54, "SkyHub");
            String kind = "E-TICKET";
            text(bold, 10, SKY, WIDTH - MARGIN - width(bold, 10, kind), HEIGHT - 40, kind);
            String what = "Itinerary receipt " + locator;
            text(regular, 14, WHITE, WIDTH - MARGIN - width(regular, 14, what), HEIGHT - 60, what);
        }

        void notes() throws IOException {
            rule(MARGIN, WIDTH - MARGIN, 88);
            text(regular, 9, MUTED, MARGIN, 74, "Times are local at each airport. Check in online on SkyHub from 24 hours before departure:");
            text(regular, 9, MUTED, MARGIN, 62, "your boarding pass comes then. Manage your booking with the reference and a passenger's last name.");
            text(bold, 9, INK, MARGIN, 44, "Itinerary receipt only: this document is not valid for travel.");
            text(regular, 9, MUTED, MARGIN, 32, "SkyHub is a portfolio project, not a real airline.");
        }

        void label(float x, float at, String text) throws IOException {
            text(bold, 9, MUTED, x, at, text);
        }

        void text(PDFont font, float size, float[] colour, float x, float at, String text) throws IOException {
            content.beginText();
            content.setFont(font, size);
            content.setNonStrokingColor(colour[0], colour[1], colour[2]);
            content.newLineAtOffset(x, at);
            content.showText(printable(font, text));
            content.endText();
        }

        float width(PDFont font, float size, String text) throws IOException {
            return font.getStringWidth(printable(font, text)) / 1000 * size;
        }

        /** The text, shortened with "..." so it is no wider than {@code max}. */
        String fit(String text, PDFont font, float size, float max) throws IOException {
            if (width(font, size, text) <= max) {
                return text;
            }
            String cut = text;
            while (cut.length() > 1 && width(font, size, cut + "...") > max) {
                cut = cut.substring(0, cut.length() - 1);
            }
            return cut.strip() + "...";
        }

        void box(float x, float bottom, float w, float h, float[] fill, float[] stroke) throws IOException {
            content.setNonStrokingColor(fill[0], fill[1], fill[2]);
            content.addRect(x, bottom, w, h);
            if (stroke == null) {
                content.fill();
                return;
            }
            content.setStrokingColor(stroke[0], stroke[1], stroke[2]);
            content.setLineWidth(1);
            content.fillAndStroke();
        }

        void rule(float from, float to, float at) throws IOException {
            content.setStrokingColor(LINE[0], LINE[1], LINE[2]);
            content.setLineWidth(1);
            content.moveTo(from, at);
            content.lineTo(to, at);
            content.stroke();
        }

        /** A line from one airport to the other with an arrowhead, in the accent colour. */
        void arrow(float from, float to, float at) throws IOException {
            content.setStrokingColor(ACCENT[0], ACCENT[1], ACCENT[2]);
            content.setNonStrokingColor(ACCENT[0], ACCENT[1], ACCENT[2]);
            content.setLineWidth(1.5f);
            content.setLineDashPattern(new float[] {4, 3}, 0);
            content.moveTo(from, at);
            content.lineTo(to - 8, at);
            content.stroke();
            content.setLineDashPattern(new float[] {}, 0);
            content.moveTo(to, at);
            content.lineTo(to - 9, at + 5);
            content.lineTo(to - 9, at - 5);
            content.closePath();
            content.fill();
        }

        /** A filled circle, drawn as four Bezier curves. */
        void circle(float cx, float cy, float r, float[] fill) throws IOException {
            float k = 0.5523f * r;
            content.setNonStrokingColor(fill[0], fill[1], fill[2]);
            content.moveTo(cx + r, cy);
            content.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r);
            content.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
            content.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r);
            content.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
            content.fill();
        }

        void close() throws IOException {
            content.close();
        }
    }

    /** The text with each letter the font cannot print replaced by "?", so an unusual name never stops the ticket being drawn. */
    private static String printable(PDFont font, String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(point -> {
            String letter = new String(Character.toChars(point));
            try {
                font.encode(letter);
                out.append(letter);
            } catch (IllegalArgumentException | IOException cannot) {
                out.append('?');
            }
        });
        return out.toString();
    }
}
