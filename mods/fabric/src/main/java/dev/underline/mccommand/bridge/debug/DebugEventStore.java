package dev.underline.mccommand.bridge.debug;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

public final class DebugEventStore {
    private static final int MAX_EVENTS = 2_048;
    private static final int MAX_TEXT_CHARACTERS = 32_000;
    private static final int MAX_COMPONENT_BYTES = 65_536;
    private static final int MAX_ERROR_CHARACTERS = 8_192;
    private static final ConcurrentLinkedDeque<DebugEvent> EVENTS = new ConcurrentLinkedDeque<>();
    private static final AtomicLong NEXT_ID = new AtomicLong();

    private DebugEventStore() {
    }

    public static synchronized DebugEvent add(
            DebugAst.Directive directive,
            String text,
            String componentJson,
            List<String> functionStack,
            boolean truncated,
            String errorCode,
            String error
    ) {
        String boundedText = bounded(text, MAX_TEXT_CHARACTERS);
        boolean componentOmitted = componentJson != null
                && componentJson.getBytes(StandardCharsets.UTF_8).length > MAX_COMPONENT_BYTES;
        DebugEvent event = new DebugEvent(
                NEXT_ID.incrementAndGet(),
                Instant.now().toEpochMilli(),
                directive.functionId(),
                directive.startLine(),
                boundedText,
                componentOmitted ? null : componentJson,
                componentOmitted,
                functionStack,
                truncated,
                errorCode,
                bounded(error, MAX_ERROR_CHARACTERS)
        );
        EVENTS.addLast(event);
        while (EVENTS.size() > MAX_EVENTS) EVENTS.pollFirst();
        return event;
    }

    public static synchronized List<DebugEvent> since(long id) {
        return snapshotSince(id).events();
    }

    public static synchronized Snapshot snapshotSince(long id) {
        return snapshotSince(id, MAX_EVENTS);
    }

    public static synchronized Snapshot snapshotSince(long id, int limit) {
        List<DebugEvent> result = new ArrayList<>();
        for (DebugEvent event : EVENTS) {
            if (event.id() > id) {
                if (result.size() >= limit) break;
                result.add(event);
            }
        }
        long latestId = NEXT_ID.get();
        long nextId = result.isEmpty() ? latestId : result.getLast().id();
        DebugEvent first = EVENTS.peekFirst();
        long oldestId = first == null ? latestId + 1 : first.id();
        return new Snapshot(
                List.copyOf(result), nextId, latestId, oldestId,
                id < oldestId - 1, nextId < latestId);
    }

    static synchronized void clearForTests() {
        EVENTS.clear();
        NEXT_ID.set(0);
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value;
        return value.substring(0, maximum - 1) + "…";
    }

    public record Snapshot(
            List<DebugEvent> events,
            long nextId,
            long latestId,
            long oldestId,
            boolean dropped,
            boolean more
    ) {
        public Snapshot {
            events = List.copyOf(events);
        }
    }
}
