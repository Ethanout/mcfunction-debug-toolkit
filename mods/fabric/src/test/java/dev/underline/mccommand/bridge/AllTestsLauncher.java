package dev.underline.mccommand.bridge;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

/** Runs every JUnit test when Gradle's Windows worker cannot encode this workspace path. */
public final class AllTestsLauncher {
    private AllTestsLauncher() {
    }

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectPackage("dev.underline.mccommand.bridge"))
                .build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        PrintWriter output = new PrintWriter(System.out, true);
        summary.printTo(output);
        if (summary.getTestsFoundCount() == 0) {
            throw new AssertionError("JUnit discovery found no tests");
        }
        if (summary.getTotalFailureCount() > 0) {
            summary.printFailuresTo(output);
            throw new AssertionError(summary.getTotalFailureCount() + " JUnit test(s) failed");
        }
    }
}
