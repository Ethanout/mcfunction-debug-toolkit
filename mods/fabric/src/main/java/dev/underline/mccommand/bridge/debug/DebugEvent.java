package dev.underline.mccommand.bridge.debug;

import java.util.List;

public record DebugEvent(
        long id,
        long timestamp,
        String functionId,
        int line,
        String text,
        String componentJson,
        boolean componentOmitted,
        List<String> functionStack,
        boolean truncated,
        String errorCode,
        String error
) {
    public DebugEvent {
        functionStack = List.copyOf(functionStack);
    }
}
