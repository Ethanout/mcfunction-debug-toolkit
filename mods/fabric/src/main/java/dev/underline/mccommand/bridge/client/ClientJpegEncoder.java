package dev.underline.mccommand.bridge.client;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class ClientJpegEncoder {
    private ClientJpegEncoder() {
    }

    static byte[] encode(BufferedImage image, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", output)) throw new IOException("JPEG encoder is unavailable");
        byte[] bytes = output.toByteArray();
        if (bytes.length > maximumBytes) {
            throw new IOException("JPEG exceeds the " + maximumBytes + " byte response limit");
        }
        return bytes;
    }
}
