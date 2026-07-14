package dev.underline.mccommand.bridge.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class ClientChatEventStore {
    private static final int MAX_MESSAGES = 2_048;
    private static final int MAX_TEXT_CHARACTERS = 32_000;
    private static final ArrayDeque<CapturedChatMessage> MESSAGES = new ArrayDeque<>();
    private static long nextIndex;

    private ClientChatEventStore() {
    }

    public static synchronized void add(int addedTime, String text, String source) {
        boolean truncated = text.length() > MAX_TEXT_CHARACTERS;
        String bounded = truncated
                ? text.substring(0, MAX_TEXT_CHARACTERS - 1) + "…"
                : text;
        MESSAGES.addLast(new CapturedChatMessage(++nextIndex, addedTime, bounded, source, truncated));
        while (MESSAGES.size() > MAX_MESSAGES) MESSAGES.removeFirst();
    }

    public static synchronized Snapshot since(long index) {
        return since(index, MAX_MESSAGES);
    }

    public static synchronized Snapshot since(long index, int limit) {
        long oldestIndex = MESSAGES.isEmpty() ? nextIndex + 1 : MESSAGES.getFirst().index();
        boolean dropped = index < oldestIndex - 1;
        List<CapturedChatMessage> result = new ArrayList<>();
        for (CapturedChatMessage message : MESSAGES) {
            if (message.index() > index) {
                if (result.size() >= limit) break;
                result.add(message);
            }
        }
        long returnedNext = result.isEmpty() ? nextIndex : result.getLast().index();
        return new Snapshot(
                List.copyOf(result), returnedNext, nextIndex, oldestIndex,
                dropped, returnedNext < nextIndex);
    }

    static synchronized void clearForTests() {
        MESSAGES.clear();
        nextIndex = 0;
    }

    public record CapturedChatMessage(
            long index,
            int addedTime,
            String text,
            String source,
            boolean truncated
    ) {
    }

    public record Snapshot(
            List<CapturedChatMessage> messages,
            long nextIndex,
            long latestIndex,
            long oldestIndex,
            boolean dropped,
            boolean more
    ) {
        public Snapshot {
            messages = List.copyOf(messages);
        }
    }
}
