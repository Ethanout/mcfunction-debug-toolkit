package dev.underline.mccommand.bridge.debug;

public final class DebugMixinGuard {
    private static final ThreadLocal<Boolean> PREPROCESSING = ThreadLocal.withInitial(() -> false);

    private DebugMixinGuard() {
    }

    public static boolean active() {
        return PREPROCESSING.get();
    }

    public static void enter() {
        PREPROCESSING.set(true);
    }

    public static void exit() {
        PREPROCESSING.remove();
    }
}
