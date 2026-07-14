package dev.underline.mccommand.bridge;

import java.util.concurrent.TimeoutException;

final class BridgeCommandTimeoutException extends TimeoutException {
    private final String requestId;
    private final boolean outcomeUnknown;

    BridgeCommandTimeoutException(String requestId, boolean outcomeUnknown) {
        super(outcomeUnknown
                ? "command execution timed out after it started; outcome is unknown"
                : "command execution timed out before it started; it was cancelled");
        this.requestId = requestId;
        this.outcomeUnknown = outcomeUnknown;
    }

    String requestId() {
        return requestId;
    }

    boolean outcomeUnknown() {
        return outcomeUnknown;
    }
}
