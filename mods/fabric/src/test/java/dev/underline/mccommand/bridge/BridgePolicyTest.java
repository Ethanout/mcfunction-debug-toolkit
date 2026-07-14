package dev.underline.mccommand.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class BridgePolicyTest {
    @Test
    void optionalCommandPrefixCannotBypassDangerPolicy() {
        assertEquals("server stop requires allow_dangerous=true", DangerPolicy.reason("/stop"));
        assertEquals("large block edits require allow_dangerous=true",
                DangerPolicy.reason(" /fill 0 0 0 1 1 1 stone"));
        assertNull(DangerPolicy.reason("say safe"));
    }

    @Test
    void commandCallbacksAreAggregatedInsteadOfLastCallbackWinning() {
        CommandCallbackSummary summary = new CommandCallbackSummary();
        assertTrue(summary.ok());
        assertFalse(summary.resultReported());
        summary.onResult(true, 4);
        summary.onResult(false, 0);
        summary.onResult(true, 7);
        assertEquals(3, summary.callbackCount());
        assertEquals(2, summary.successCount());
        assertEquals(1, summary.failureCount());
        assertEquals(11, summary.resultSum());
        assertFalse(summary.ok());
    }

    @Test
    void cancelledServerTaskCannotStartAndStartedTaskHasUnknownOutcome() {
        ServerTaskControl queued = new ServerTaskControl(60_000);
        queued.cancel();
        assertFalse(queued.tryStart());
        assertFalse(queued.started());

        ServerTaskControl started = new ServerTaskControl(60_000);
        assertTrue(started.tryStart());
        started.cancel();
        assertTrue(started.started());
        assertTrue(started.shouldStop());
    }

    @Test
    void responsePageBudgetEnforcesBothCountAndBytes() {
        ResponsePageBudget count = new ResponsePageBudget(1, 1_024);
        JsonObject small = new JsonObject();
        small.addProperty("value", "ok");
        assertTrue(count.tryAdd(small));
        assertFalse(count.tryAdd(small));

        ResponsePageBudget bytes = new ResponsePageBudget(10, 16);
        JsonObject large = new JsonObject();
        large.addProperty("value", "this is too large");
        assertFalse(bytes.tryAdd(large));
    }

    @Test
    void feedbackBufferBoundsIndividualAndTotalMessages() {
        BoundedTextBuffer individual = new BoundedTextBuffer(4, 20, 5);
        individual.add("123456789");
        assertEquals(List.of("1234…"), individual.messages());
        assertTrue(individual.truncated());

        BoundedTextBuffer count = new BoundedTextBuffer(1, 20, 20);
        count.add("first");
        count.add("second");
        assertEquals(List.of("first"), count.messages());
        assertTrue(count.truncated());
    }

    public static void runAll() {
        BridgePolicyTest test = new BridgePolicyTest();
        test.optionalCommandPrefixCannotBypassDangerPolicy();
        test.commandCallbacksAreAggregatedInsteadOfLastCallbackWinning();
        test.cancelledServerTaskCannotStartAndStartedTaskHasUnknownOutcome();
        test.responsePageBudgetEnforcesBothCountAndBytes();
        test.feedbackBufferBoundsIndividualAndTotalMessages();
    }
}
