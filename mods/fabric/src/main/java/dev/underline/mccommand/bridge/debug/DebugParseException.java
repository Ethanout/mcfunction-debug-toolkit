package dev.underline.mccommand.bridge.debug;

public final class DebugParseException extends Exception {
    private final String code;
    private final int offset;

    public DebugParseException(String code, int offset, String message) {
        super(message);
        this.code = code;
        this.offset = offset;
    }

    public String code() {
        return code;
    }

    public int offset() {
        return offset;
    }
}
