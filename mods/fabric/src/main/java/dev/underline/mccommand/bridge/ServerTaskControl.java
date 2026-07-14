package dev.underline.mccommand.bridge;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class ServerTaskControl {
    private final String requestId = UUID.randomUUID().toString();
    private final long deadlineNanos;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    ServerTaskControl(long timeoutMillis) {
        deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
    }

    boolean tryStart() {
        if (cancelled.get() || expired()) return false;
        if (!started.compareAndSet(false, true)) return false;
        return !cancelled.get();
    }

    void cancel() {
        cancelled.set(true);
    }

    boolean shouldStop() {
        return cancelled.get() || expired();
    }

    boolean started() {
        return started.get();
    }

    String requestId() {
        return requestId;
    }

    private boolean expired() {
        return System.nanoTime() >= deadlineNanos;
    }
}
