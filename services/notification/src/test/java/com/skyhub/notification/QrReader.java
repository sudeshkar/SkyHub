package com.skyhub.notification;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Reads the QR code out of a PNG, as a phone's camera would, so tests can check the picture says what it should. */
public final class QrReader {

    private QrReader() {
    }

    public static String textOf(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
            return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels)))).getText();
        } catch (IOException | NotFoundException | com.google.zxing.ChecksumException | com.google.zxing.FormatException e) {
            throw new IllegalStateException("No QR code could be read from the picture", e);
        }
    }
}
