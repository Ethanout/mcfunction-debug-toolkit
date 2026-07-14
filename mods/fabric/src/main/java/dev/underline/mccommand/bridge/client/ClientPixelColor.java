package dev.underline.mccommand.bridge.client;

public final class ClientPixelColor {
    private ClientPixelColor() {
    }

    public static int rgbFromArgb(int argb) {
        return ((argb >>> 16) & 0xff) << 16
                | ((argb >>> 8) & 0xff) << 8
                | (argb & 0xff);
    }
}
