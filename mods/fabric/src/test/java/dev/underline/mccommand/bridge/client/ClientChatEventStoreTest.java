package dev.underline.mccommand.bridge.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ClientChatEventStoreTest {
    @BeforeEach
    void reset() {
        ClientChatEventStore.clearForTests();
    }

    @Test
    void cursorReturnsOnlyMessagesAddedAfterThePreviousPoll() {
        ClientChatEventStore.add(10, "first", "system_server");
        ClientChatEventStore.Snapshot first = ClientChatEventStore.since(0);
        assertEquals(1, first.nextIndex());
        assertEquals("first", first.messages().getFirst().text());
        assertFalse(first.dropped());

        ClientChatEventStore.add(11, "second", "system_server");
        ClientChatEventStore.Snapshot second = ClientChatEventStore.since(first.nextIndex());
        assertEquals(2, second.nextIndex());
        assertEquals(1, second.messages().size());
        assertEquals("second", second.messages().getFirst().text());
        assertTrue(ClientChatEventStore.since(second.nextIndex()).messages().isEmpty());
    }

    @Test
    void reportsWhenTheCallerFallsBehindTheBoundedBuffer() {
        for (int index = 0; index < 2_050; index++) {
            ClientChatEventStore.add(index, "message-" + index, "system_server");
        }
        ClientChatEventStore.Snapshot snapshot = ClientChatEventStore.since(0);
        assertEquals(2_050, snapshot.nextIndex());
        assertEquals(3, snapshot.oldestIndex());
        assertEquals(2_048, snapshot.messages().size());
        assertTrue(snapshot.dropped());
    }

    @Test
    void pagesAndBoundsIndividualChatMessages() {
        ClientChatEventStore.add(1, "a", "system_server");
        ClientChatEventStore.add(2, "b", "system_server");
        ClientChatEventStore.add(3, "x".repeat(33_000), "system_server");
        ClientChatEventStore.Snapshot first = ClientChatEventStore.since(0, 2);
        assertEquals(2, first.messages().size());
        assertEquals(2, first.nextIndex());
        assertEquals(3, first.latestIndex());
        assertTrue(first.more());
        ClientChatEventStore.Snapshot second = ClientChatEventStore.since(first.nextIndex(), 2);
        assertEquals(3, second.nextIndex());
        assertTrue(second.messages().getFirst().truncated());
        assertEquals(32_000, second.messages().getFirst().text().length());
        assertFalse(second.more());
    }

    public static void runAll() {
        ClientChatEventStoreTest test = new ClientChatEventStoreTest();
        test.reset();
        test.cursorReturnsOnlyMessagesAddedAfterThePreviousPoll();
        test.reset();
        test.reportsWhenTheCallerFallsBehindTheBoundedBuffer();
    }
}
