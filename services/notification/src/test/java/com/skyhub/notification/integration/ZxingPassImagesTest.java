package com.skyhub.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.skyhub.notification.QrReader;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** The QR picture of a boarding pass (ADR-0074): a PNG a phone can open with no signal and a gate can read. */
class ZxingPassImagesTest {

    private static final String PASS = "M1PERERA/PRIYA ABC234 SK501 2026-11-02 11D 0007.dGVzdC1zaWduYXR1cmUtb25lLW5vdC1yZWFsLWFhYWFhYWE";
    private final ZxingPassImages images = new ZxingPassImages();

    @Test
    void should_draw_a_png_that_a_reader_decodes_to_exactly_the_pass_text() {
        byte[] png = images.qrPng(PASS);

        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(QrReader.textOf(png)).isEqualTo(PASS);
    }

    @Test
    void should_hold_the_longest_pass_the_gate_accepts() {
        String longest = PASS.substring(0, 7) + "X".repeat(300 - 7);

        assertThat(QrReader.textOf(images.qrPng(longest))).isEqualTo(longest);
    }

    @Test
    void should_be_large_enough_to_read_on_a_phone_and_small_enough_to_attach() throws Exception {
        byte[] png = images.qrPng(PASS);
        BufferedImage picture = ImageIO.read(new ByteArrayInputStream(png));

        assertThat(picture.getWidth()).isBetween(300, 700).isEqualTo(picture.getHeight());
        assertThat(png.length).isLessThan(20_000);
    }

    @Test
    void should_leave_a_quiet_zone_of_white_round_the_code_which_a_scanner_needs() throws Exception {
        BufferedImage picture = ImageIO.read(new ByteArrayInputStream(images.qrPng(PASS)));
        int margin = picture.getWidth() / 12;   // at least four modules of a code of about 41 modules

        for (int x = 0; x < margin; x++) {
            for (int y = 0; y < picture.getHeight(); y++) {
                assertThat(picture.getRGB(x, y) & 0xFFFFFF).isEqualTo(0xFFFFFF);
            }
        }
    }

    @Test
    void should_draw_the_same_picture_for_the_same_pass_and_a_different_one_for_another() {
        assertThat(images.qrPng(PASS)).isEqualTo(images.qrPng(PASS));
        assertThat(images.qrPng(PASS.replace("11D", "11E"))).isNotEqualTo(images.qrPng(PASS));
    }

    @Test
    void should_refuse_to_draw_nothing() {
        assertThatIllegalArgumentException().isThrownBy(() -> images.qrPng(""));
        assertThatIllegalArgumentException().isThrownBy(() -> images.qrPng(null));
    }
}
