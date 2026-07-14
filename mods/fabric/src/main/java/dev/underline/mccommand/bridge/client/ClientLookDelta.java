package dev.underline.mccommand.bridge.client;

public final class ClientLookDelta {
    private static final double VANILLA_TURN_SCALE = 0.15;

    private ClientLookDelta() {
    }

    public static double turnUnitsForDegrees(double degrees) {
        return degrees / VANILLA_TURN_SCALE;
    }
}
