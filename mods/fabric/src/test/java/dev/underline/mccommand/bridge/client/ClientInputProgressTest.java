package dev.underline.mccommand.bridge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientInputProgressTest {
    @Test
    void activeTimedStepIsNotCompletedUntilItsDurationEnds() {
        ClientInputProgress progress = new ClientInputProgress(2);
        assertEquals(0, progress.startNext());
        progress.beginTimedStep();
        assertEquals(0, progress.completedSteps());
        assertTrue(progress.activeTimedStep());
        assertTrue(progress.completeTimedStep());
        assertEquals(1, progress.completedSteps());

        assertEquals(1, progress.startNext());
        progress.completeImmediateStep();
        assertEquals(2, progress.completedSteps());
        assertFalse(progress.hasNext());
    }

    @Test
    void cancellationCanReportOnlyPreviouslyCompletedSteps() {
        ClientInputProgress progress = new ClientInputProgress(2);
        progress.startNext();
        progress.completeImmediateStep();
        progress.startNext();
        progress.beginTimedStep();
        assertEquals(1, progress.completedSteps());
    }
}
