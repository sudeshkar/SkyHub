package com.skyhub.notification.integration;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.skyhub.notification.application.PassImages;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Adapter: ZXing draws the code (ADR-0074). Level M error correction (as the website's, ADR-0071), a four-module quiet zone, and eight
 * pixels to a module, so the picture is a whole number of pixels to a module and stays sharp when a phone scales it. The result is a
 * one-bit PNG of a few kilobytes. Nothing is stored, logged or sent from here: the text goes in, the picture comes out.
 */
@Component
public class ZxingPassImages implements PassImages {

    private static final int MODULE_PIXELS = 8;
    private static final int QUIET_MODULES = 4;
    private static final int BLACK = 0x000000;
    private static final int WHITE = 0xFFFFFF;

    @Override
    public byte[] qrPng(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("There is nothing to draw a code of");
        }
        try {
            // A width and height of 0 asks for the code at its own size, quiet zone included, one pixel to a module.
            BitMatrix modules = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, QUIET_MODULES,
                    EncodeHintType.CHARACTER_SET, "UTF-8"));
            int size = modules.getWidth() * MODULE_PIXELS;
            BufferedImage picture = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    picture.setRGB(x, y, modules.get(x / MODULE_PIXELS, y / MODULE_PIXELS) ? BLACK : WHITE);
                }
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(picture, "png", png)) {
                throw new IllegalStateException("This runtime cannot write a PNG");
            }
            return png.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("The boarding pass could not be drawn", e);
        }
    }
}
