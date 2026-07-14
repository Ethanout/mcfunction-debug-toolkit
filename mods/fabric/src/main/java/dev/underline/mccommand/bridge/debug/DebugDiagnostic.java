package dev.underline.mccommand.bridge.debug;

public record DebugDiagnostic(
        long id,
        long timestamp,
        String phase,
        String code,
        String functionId,
        int line,
        int column,
        String message,
        String source
) {
}
