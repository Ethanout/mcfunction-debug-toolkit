package dev.underline.mccommand.bridge.client;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class ClientInputRequestTest {
    @Test
    void parsesValidatedImmutableStepsAndDefaults() {
        ClientInputRequest request = parse("""
                {
                  "steps": [
                    {"type":"key","key":"W"},
                    {"type":"mouse","button":"right"},
                    {"type":"mouse","button":"left","action":"double_click","interval_ms":80},
                    {"type":"cursor","x":0.25,"y":0.75},
                    {"type":"cursor","action":"move_relative","dx":20,"dy":-10,"coordinate_space":"pixel"},
                    {"type":"drag","from_x":0.1,"from_y":0.2,"to_x":0.8,"to_y":0.9},
                    {"type":"look","yaw_delta":12.5},
                    {"type":"wait","duration_ms":250}
                  ],
                  "total_timeout_ms":5000
                }
                """);
        assertEquals(8, request.steps().size());
        assertEquals("w", request.steps().get(0).name());
        assertEquals("press", request.steps().get(0).action());
        assertEquals(100, request.steps().get(0).durationMs());
        assertEquals("click", request.steps().get(1).action());
        assertEquals("double_click", request.steps().get(2).action());
        assertEquals(80, request.steps().get(2).intervalMs());
        assertEquals(0.25, request.steps().get(3).x());
        assertEquals(20, request.steps().get(4).dx());
        assertEquals(0.8, request.steps().get(5).toX());
        assertEquals(12.5, request.steps().get(6).yawDelta());
        assertEquals(5_000, request.timeoutMs());
        assertThrows(UnsupportedOperationException.class,
                () -> request.steps().add(request.steps().getFirst()));
    }

    @Test
    void rejectsMalformedStepsBeforeTheyReachTheRenderThread() {
        assertEquals("steps[0] must be an object",
                invalid("{\"steps\":[42]}").getMessage());
        assertEquals("steps[0] 'duration_ms' must be an integer from 0 to 30000",
                invalid("{\"steps\":[{\"type\":\"wait\",\"duration_ms\":1.5}]}").getMessage());
        assertEquals("steps[0] unsupported key 'f13'",
                invalid("{\"steps\":[{\"type\":\"key\",\"key\":\"F13\"}]}").getMessage());
        assertEquals("steps[0] 'yaw_delta' must be from -1800.0 to 1800.0",
                invalid("{\"steps\":[{\"type\":\"look\",\"yaw_delta\":2000}]}").getMessage());
        assertEquals("steps[0] missing 'y'",
                invalid("{\"steps\":[{\"type\":\"cursor\",\"x\":0.5}]}").getMessage());
        assertEquals("steps[0] 'to_x' must be from 0.0 to 1.0",
                invalid("{\"steps\":[{\"type\":\"drag\",\"from_x\":0,\"from_y\":0,\"to_x\":2,\"to_y\":1}]}").getMessage());
        assertEquals("'total_timeout_ms' must be an integer from 1000 to 120000",
                invalid("{\"steps\":[{\"type\":\"wait\",\"duration_ms\":0}],\"total_timeout_ms\":999}")
                        .getMessage());
    }

    private static ClientInputRequest parse(String json) {
        return ClientInputRequest.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    private static IllegalArgumentException invalid(String json) {
        return assertThrows(IllegalArgumentException.class, () -> parse(json));
    }

    public static void runAll() {
        ClientInputRequestTest test = new ClientInputRequestTest();
        test.parsesValidatedImmutableStepsAndDefaults();
        test.rejectsMalformedStepsBeforeTheyReachTheRenderThread();
    }
}
