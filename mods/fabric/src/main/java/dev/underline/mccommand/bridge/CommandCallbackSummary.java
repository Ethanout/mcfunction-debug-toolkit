package dev.underline.mccommand.bridge;

import net.minecraft.commands.CommandResultCallback;

final class CommandCallbackSummary implements CommandResultCallback {
    private int callbackCount;
    private int successCount;
    private int failureCount;
    private long resultSum;

    @Override
    public synchronized void onResult(boolean successful, int result) {
        callbackCount++;
        if (successful) successCount++;
        else failureCount++;
        resultSum += result;
    }

    synchronized int callbackCount() {
        return callbackCount;
    }

    synchronized int successCount() {
        return successCount;
    }

    synchronized int failureCount() {
        return failureCount;
    }

    synchronized long resultSum() {
        return resultSum;
    }

    synchronized boolean resultReported() {
        return callbackCount > 0;
    }

    synchronized boolean ok() {
        return callbackCount == 0 || failureCount == 0;
    }
}
