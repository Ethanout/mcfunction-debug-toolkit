package dev.underline.mccommand.bridge.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record ClientInputRequest(List<Step> steps, long timeoutMs) {
    private static final long DEFAULT_TIMEOUT_MS = 120_000;
    private static final Set<String> KEY_NAMES = Set.of(
            "forward", "up", "w", "back", "down", "s", "left", "a", "right", "d",
            "jump", "space", "sneak", "shift", "sprint", "ctrl", "control",
            "use", "right_click", "attack", "left_click"
    );

    public ClientInputRequest {
        steps = List.copyOf(steps);
    }

    public static ClientInputRequest parse(JsonObject request) {
        if (request == null) throw new IllegalArgumentException("request body must be a JSON object");
        JsonElement stepsElement = request.get("steps");
        JsonArray steps = stepsElement != null && stepsElement.isJsonArray()
                ? stepsElement.getAsJsonArray()
                : null;
        if (steps == null || steps.isEmpty() || steps.size() > 200) {
            throw new IllegalArgumentException("steps must contain 1 to 200 items");
        }

        List<Step> result = new ArrayList<>(steps.size());
        for (int index = 0; index < steps.size(); index++) {
            JsonElement element = steps.get(index);
            if (!element.isJsonObject()) throw invalidStep(index, "must be an object");
            JsonObject step = element.getAsJsonObject();
            String type = requiredString(step, "type", index);
            switch (type) {
                case "wait" -> result.add(new Step(
                        type, "", "", requiredInteger(step, "duration_ms", index, 0, 30_000), 0, 0));
                case "look" -> result.add(new Step(
                        type,
                        "",
                        "",
                        0,
                        optionalDouble(step, "yaw_delta", 0, index, -1_800, 1_800),
                        optionalDouble(step, "pitch_delta", 0, index, -1_800, 1_800)
                ));
                case "key" -> {
                    String key = requiredString(step, "key", index).toLowerCase(Locale.ROOT);
                    if (key.length() > 32 || (!KEY_NAMES.contains(key) && key.length() != 1)) {
                        throw invalidStep(index, "unsupported key '" + key + "'");
                    }
                    String action = optionalString(step, "action", "press", index);
                    if (!Set.of("press", "release", "hold").contains(action)) {
                        throw invalidStep(index, "invalid key action '" + action + "'");
                    }
                    result.add(new Step(
                            type, key, action, optionalInteger(step, "duration_ms", 100, 0, 30_000), 0, 0));
                }
                case "mouse" -> {
                    String button = requiredString(step, "button", index).toLowerCase(Locale.ROOT);
                    if (!Set.of("left", "right", "middle").contains(button)) {
                        throw invalidStep(index, "invalid mouse button '" + button + "'");
                    }
                    String action = optionalString(step, "action", "click", index);
                    if (!Set.of("press", "release", "click", "double_click", "hold").contains(action)) {
                        throw invalidStep(index, "invalid mouse action '" + action + "'");
                    }
                    result.add(new Step(
                            type, button, action, optionalInteger(step, "duration_ms", 100, 0, 30_000), 0, 0,
                            Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                            Double.NaN, Double.NaN, Double.NaN, Double.NaN, "",
                            optionalInteger(step, "interval_ms", 100, 20, 1_000)));
                }
                case "cursor" -> {
                    String action = optionalString(step, "action", "move", index);
                    if (!Set.of("move", "move_relative").contains(action)) {
                        throw invalidStep(index, "invalid cursor action '" + action + "'");
                    }
                    String coordinateSpace = optionalString(step, "coordinate_space", "normalized", index);
                    if (!Set.of("normalized", "pixel").contains(coordinateSpace)) {
                        throw invalidStep(index, "invalid coordinate_space '" + coordinateSpace + "'");
                    }
                    double x = Double.NaN;
                    double y = Double.NaN;
                    double dx = Double.NaN;
                    double dy = Double.NaN;
                    if (action.equals("move")) {
                        double maximum = coordinateSpace.equals("normalized") ? 1 : 100_000;
                        x = requiredDouble(step, "x", index, 0, maximum);
                        y = requiredDouble(step, "y", index, 0, maximum);
                    } else {
                        dx = requiredDouble(step, "dx", index, -100_000, 100_000);
                        dy = requiredDouble(step, "dy", index, -100_000, 100_000);
                    }
                    result.add(new Step(type, "", action, 0, 0, 0, x, y, dx, dy,
                            Double.NaN, Double.NaN, Double.NaN, Double.NaN, coordinateSpace, 0));
                }
                case "drag" -> {
                    String button = optionalString(step, "button", "left", index).toLowerCase(Locale.ROOT);
                    if (!Set.of("left", "right", "middle").contains(button)) {
                        throw invalidStep(index, "invalid mouse button '" + button + "'");
                    }
                    String coordinateSpace = optionalString(step, "coordinate_space", "normalized", index);
                    if (!Set.of("normalized", "pixel").contains(coordinateSpace)) {
                        throw invalidStep(index, "invalid coordinate_space '" + coordinateSpace + "'");
                    }
                    double maximum = coordinateSpace.equals("normalized") ? 1 : 100_000;
                    result.add(new Step(type, button, "drag",
                            optionalInteger(step, "duration_ms", 500, 0, 30_000), 0, 0,
                            Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                            requiredDouble(step, "from_x", index, 0, maximum),
                            requiredDouble(step, "from_y", index, 0, maximum),
                            requiredDouble(step, "to_x", index, 0, maximum),
                            requiredDouble(step, "to_y", index, 0, maximum), coordinateSpace, 0));
                }
                default -> throw invalidStep(index, "unknown type '" + type + "'");
            }
        }

        long timeoutMs = optionalInteger(request, "total_timeout_ms", DEFAULT_TIMEOUT_MS, 1_000, 120_000);
        return new ClientInputRequest(result, timeoutMs);
    }

    private static String requiredString(JsonObject object, String key, int stepIndex) {
        if (!object.has(key)) throw invalidStep(stepIndex, "missing '" + key + "'");
        return optionalString(object, key, null, stepIndex);
    }

    private static String optionalString(JsonObject object, String key, String fallback, int stepIndex) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalidStep(stepIndex, "'" + key + "' must be a string");
        }
        String result = value.getAsString();
        if (result.isEmpty()) throw invalidStep(stepIndex, "'" + key + "' cannot be empty");
        return result;
    }

    private static long requiredInteger(
            JsonObject object, String key, int stepIndex, long minimum, long maximum) {
        if (!object.has(key)) throw invalidStep(stepIndex, "missing '" + key + "'");
        return integerValue(object.get(key), key, stepIndex, minimum, maximum);
    }

    private static long optionalInteger(
            JsonObject object, String key, long fallback, long minimum, long maximum) {
        if (!object.has(key)) return fallback;
        return integerValue(object.get(key), key, -1, minimum, maximum);
    }

    private static long integerValue(
            JsonElement value, String key, int stepIndex, long minimum, long maximum) {
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            long result = new BigDecimal(value.getAsString()).longValueExact();
            if (result < minimum || result > maximum) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException | NumberFormatException error) {
            String message = "'" + key + "' must be an integer from " + minimum + " to " + maximum;
            if (stepIndex >= 0) throw invalidStep(stepIndex, message);
            throw new IllegalArgumentException(message);
        }
    }

    private static double optionalDouble(
            JsonObject object,
            String key,
            double fallback,
            int stepIndex,
            double minimum,
            double maximum
    ) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalidStep(stepIndex, "'" + key + "' must be a number");
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result) || result < minimum || result > maximum) {
            throw invalidStep(stepIndex, "'" + key + "' must be from " + minimum + " to " + maximum);
        }
        return result;
    }

    private static double requiredDouble(
            JsonObject object, String key, int stepIndex, double minimum, double maximum) {
        if (!object.has(key)) throw invalidStep(stepIndex, "missing '" + key + "'");
        return optionalDouble(object, key, Double.NaN, stepIndex, minimum, maximum);
    }

    private static IllegalArgumentException invalidStep(int index, String message) {
        return new IllegalArgumentException("steps[" + index + "] " + message);
    }

    public record Step(
            String type,
            String name,
            String action,
            long durationMs,
            double yawDelta,
            double pitchDelta,
            double x,
            double y,
            double dx,
            double dy,
            double fromX,
            double fromY,
            double toX,
            double toY,
            String coordinateSpace,
            long intervalMs
    ) {
        public Step(
                String type,
                String name,
                String action,
                long durationMs,
                double yawDelta,
                double pitchDelta
        ) {
            this(type, name, action, durationMs, yawDelta, pitchDelta,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, "", 0);
        }
    }
}
