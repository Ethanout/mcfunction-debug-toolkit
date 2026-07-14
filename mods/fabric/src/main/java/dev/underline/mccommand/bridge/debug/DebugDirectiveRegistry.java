package dev.underline.mccommand.bridge.debug;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class DebugDirectiveRegistry {
    private static final int MAX_DIAGNOSTICS = 1_024;
    private static final int MAX_DIAGNOSTIC_CHARACTERS = 16_384;
    private static final AtomicReference<Map<String, DebugAst.Directive>> DIRECTIVES =
            new AtomicReference<>(Map.of());
    private static final ConcurrentLinkedDeque<DebugDiagnostic> DIAGNOSTICS = new ConcurrentLinkedDeque<>();
    private static final AtomicLong NEXT_DIAGNOSTIC_ID = new AtomicLong();
    private static final AtomicLong NEXT_RELOAD_ID = new AtomicLong();
    private static final AtomicReference<ReloadState> RELOAD = new AtomicReference<>();

    private DebugDirectiveRegistry() {
    }

    public static String register(DebugAst.Directive directive) {
        String id = sha256(directive.functionId() + "\n" + directive.startLine() + "\n" + directive.source());
        ReloadState reload = RELOAD.get();
        if (reload != null) {
            reload.staging().put(id, directive);
        } else {
            DIRECTIVES.updateAndGet(current -> {
                Map<String, DebugAst.Directive> updated = new HashMap<>(current);
                updated.put(id, directive);
                return Map.copyOf(updated);
            });
        }
        return id;
    }

    public static DebugAst.Directive get(String id) {
        return DIRECTIVES.get().get(id);
    }

    public static void clearForReload() {
        DIRECTIVES.set(Map.of());
        DIAGNOSTICS.clear();
        RELOAD.set(null);
    }

    public static long beginReload() {
        long id = NEXT_RELOAD_ID.incrementAndGet();
        RELOAD.set(new ReloadState(
                id,
                DIRECTIVES.get(),
                new ConcurrentHashMap<>()
        ));
        DIAGNOSTICS.clear();
        DebugDirectiveExecutor.clearRuntimeState();
        return id;
    }

    public static void finishReload(long id, boolean successful) {
        ReloadState reload = RELOAD.get();
        if (reload == null || reload.id() != id || !RELOAD.compareAndSet(reload, null)) return;
        if (successful) DIRECTIVES.set(Map.copyOf(reload.staging()));
    }

    public static synchronized DebugDiagnostic addDiagnostic(
            String phase,
            String code,
            String functionId,
            int line,
            int column,
            String message,
            String source
    ) {
        DebugDiagnostic diagnostic = new DebugDiagnostic(
                NEXT_DIAGNOSTIC_ID.incrementAndGet(),
                Instant.now().toEpochMilli(),
                phase,
                code,
                functionId,
                line,
                column,
                bounded(message, MAX_DIAGNOSTIC_CHARACTERS),
                bounded(source, MAX_DIAGNOSTIC_CHARACTERS)
        );
        DIAGNOSTICS.addLast(diagnostic);
        while (DIAGNOSTICS.size() > MAX_DIAGNOSTICS) {
            DIAGNOSTICS.pollFirst();
        }
        return diagnostic;
    }

    public static synchronized List<DebugDiagnostic> diagnosticsSince(long id) {
        return diagnosticsSnapshotSince(id).diagnostics();
    }

    public static synchronized DiagnosticSnapshot diagnosticsSnapshotSince(long id) {
        return diagnosticsSnapshotSince(id, MAX_DIAGNOSTICS);
    }

    public static synchronized DiagnosticSnapshot diagnosticsSnapshotSince(long id, int limit) {
        List<DebugDiagnostic> result = new ArrayList<>();
        for (DebugDiagnostic diagnostic : DIAGNOSTICS) {
            if (diagnostic.id() > id) {
                if (result.size() >= limit) break;
                result.add(diagnostic);
            }
        }
        long latestId = NEXT_DIAGNOSTIC_ID.get();
        long nextId = result.isEmpty() ? latestId : result.getLast().id();
        DebugDiagnostic first = DIAGNOSTICS.peekFirst();
        long oldestId = first == null ? latestId + 1 : first.id();
        return new DiagnosticSnapshot(
                List.copyOf(result), nextId, latestId, oldestId,
                id < oldestId - 1, nextId < latestId);
    }

    static int directiveCount() {
        return DIRECTIVES.get().size();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value;
        return value.substring(0, maximum - 1) + "…";
    }

    private record ReloadState(
            long id,
            Map<String, DebugAst.Directive> previous,
            ConcurrentHashMap<String, DebugAst.Directive> staging
    ) {
    }

    public record DiagnosticSnapshot(
            List<DebugDiagnostic> diagnostics,
            long nextId,
            long latestId,
            long oldestId,
            boolean dropped,
            boolean more
    ) {
        public DiagnosticSnapshot {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
