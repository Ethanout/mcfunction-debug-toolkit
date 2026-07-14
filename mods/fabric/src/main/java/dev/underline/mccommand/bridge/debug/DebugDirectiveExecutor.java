package dev.underline.mccommand.bridge.debug;

import com.mojang.serialization.JsonOps;
import dev.underline.mccommand.bridge.McCommandBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

final class DebugDirectiveExecutor {
    private static final long ERROR_RATE_LIMIT_MS = 5_000;
    private static final int MAX_ERROR_KEYS = 4_096;
    private static final ConcurrentHashMap<String, Long> LAST_ERROR = new ConcurrentHashMap<>();
    private static final DebugDirectiveRenderer RENDERER = new DebugDirectiveRenderer();

    private DebugDirectiveExecutor() {
    }

    static void execute(CommandSourceStack source, String id, int frameDepth) {
        DebugAst.Directive directive = DebugDirectiveRegistry.get(id);
        if (directive == null) {
            broadcast(source, Component.literal("[#!] directive is unavailable; reload the datapack")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        try {
            DebugDirectiveRenderer.Rendered rendered = RENDERER.render(source, directive, frameDepth);
            broadcast(source, rendered.component());
            DebugEventStore.add(
                    directive,
                    rendered.component().getString(),
                    componentJson(source, rendered.component()),
                    rendered.functionStack().stream().map(Identifier::toString).toList(),
                    rendered.truncated(),
                    null,
                    null
            );
        } catch (DebugRuntimeException error) {
            runtimeError(source, directive, frameDepth, error.code(), error.getMessage(), error.getMessage(), error);
        } catch (RuntimeException error) {
            String detail = error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage());
            runtimeError(source, directive, frameDepth, "internal_error", "internal debug error", detail, error);
        }
    }

    private static void runtimeError(
            CommandSourceStack source,
            DebugAst.Directive directive,
            int frameDepth,
            String code,
            String chatMessage,
            String detail,
            Throwable error
    ) {
        String shortMessage = chatMessage == null ? code : chatMessage;
        if (shortMessage.length() > 160) shortMessage = shortMessage.substring(0, 159) + "…";
        String chat = "[#! " + directive.functionId() + ":" + directive.startLine() + "] " + shortMessage;
        broadcast(source, Component.literal(chat).withStyle(ChatFormatting.RED));

        List<String> stack = DebugFunctionStack.throughDepth(frameDepth).stream().map(Identifier::toString).toList();
        DebugEventStore.add(directive, "", null, stack, false, code, detail);

        String key = directive.functionId() + ":" + directive.startLine() + ":" + code;
        long now = System.currentTimeMillis();
        if (shouldLog(key, now)) {
            DebugDirectiveRegistry.addDiagnostic(
                    "runtime", code, directive.functionId(), directive.startLine(), 1,
                    detail, directive.source());
            McCommandBridge.LOGGER.warn(
                    "#! runtime error at {}:{} [{}]: {}",
                    directive.functionId(), directive.startLine(), code, detail, error);
        }
    }

    private static synchronized boolean shouldLog(String key, long now) {
        Long previous = LAST_ERROR.put(key, now);
        if (LAST_ERROR.size() > MAX_ERROR_KEYS) {
            String oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (var entry : LAST_ERROR.entrySet()) {
                if (entry.getValue() < oldestTime) {
                    oldest = entry.getKey();
                    oldestTime = entry.getValue();
                }
            }
            if (oldest != null && !oldest.equals(key)) LAST_ERROR.remove(oldest);
        }
        return previous == null || now - previous >= ERROR_RATE_LIMIT_MS;
    }

    static void clearRuntimeState() {
        LAST_ERROR.clear();
    }

    private static void broadcast(CommandSourceStack source, Component component) {
        source.getServer().getPlayerList().getPlayers().forEach(player -> player.sendSystemMessage(component));
    }

    private static String componentJson(CommandSourceStack source, Component component) {
        return ComponentSerialization.CODEC
                .encodeStart(RegistryOps.create(JsonOps.INSTANCE, source.registryAccess()), component)
                .result()
                .map(Object::toString)
                .orElse(null);
    }
}
