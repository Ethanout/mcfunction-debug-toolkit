package dev.underline.mccommand.bridge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class ClientLookDeltaTest {
    @Test
    void convertsDegreesToVanillaTurnUnits() {
        assertEquals(600.0, ClientLookDelta.turnUnitsForDegrees(90.0));
        assertEquals(-300.0, ClientLookDelta.turnUnitsForDegrees(-45.0));
    }

    public static void runAll() {
        new ClientLookDeltaTest().convertsDegreesToVanillaTurnUnits();
    }
}
