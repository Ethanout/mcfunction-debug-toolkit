package dev.underline.mccommand.bridge.debug;

final class DebugRuntimeException extends Exception {
    private final String code;

    DebugRuntimeException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
