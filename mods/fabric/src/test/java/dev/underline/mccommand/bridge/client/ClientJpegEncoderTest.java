package dev.underline.mccommand.bridge.client;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientJpegEncoderTest {
    @Test
    void encodesOnceToBoundedJpegBytes() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x00ff0000);
        byte[] bytes = ClientJpegEncoder.encode(image, 16_384);
        assertTrue(bytes.length > 2);
        assertEquals(0xff, bytes[0] & 0xff);
        assertEquals(0xd8, bytes[1] & 0xff);
        assertThrows(IOException.class, () -> ClientJpegEncoder.encode(image, 1));
    }
}
