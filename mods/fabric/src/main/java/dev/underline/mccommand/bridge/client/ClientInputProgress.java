package dev.underline.mccommand.bridge.client;

final class ClientInputProgress {
    private final int totalSteps;
    private int nextIndex;
    private int completedSteps;
    private boolean activeTimedStep;

    ClientInputProgress(int totalSteps) {
        this.totalSteps = totalSteps;
    }

    boolean hasNext() {
        return nextIndex < totalSteps;
    }

    int startNext() {
        if (activeTimedStep || !hasNext()) throw new IllegalStateException("cannot start the next input step");
        return nextIndex++;
    }

    void beginTimedStep() {
        if (activeTimedStep) throw new IllegalStateException("an input step is already active");
        activeTimedStep = true;
    }

    void completeImmediateStep() {
        if (activeTimedStep || completedSteps >= nextIndex) {
            throw new IllegalStateException("no immediate input step is pending");
        }
        completedSteps++;
    }

    boolean completeTimedStep() {
        if (!activeTimedStep) return false;
        activeTimedStep = false;
        completedSteps++;
        return true;
    }

    boolean activeTimedStep() {
        return activeTimedStep;
    }

    int completedSteps() {
        return completedSteps;
    }
}
