package dev.underline.mccommand.bridge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class ClientPixelColorTest {
    @Test
    void convertsNativeArgbToBufferedImageRgbWithoutSwappingChannels() {
        assertEquals(0x123456, ClientPixelColor.rgbFromArgb(0xff123456));
        assertEquals(0xabcdef, ClientPixelColor.rgbFromArgb(0x80abcdef));
    }

    public static void runAll() {
        new ClientPixelColorTest().convertsNativeArgbToBufferedImageRgbWithoutSwappingChannels();
    }
}
