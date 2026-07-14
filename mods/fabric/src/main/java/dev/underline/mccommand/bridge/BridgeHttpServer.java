package dev.underline.mccommand.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.underline.mccommand.bridge.debug.DebugDiagnostic;
import dev.underline.mccommand.bridge.debug.DebugDirectiveRegistry;
import dev.underline.mccommand.bridge.debug.DebugEvent;
import dev.underline.mccommand.bridge.debug.DebugEventStore;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionSet;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

final class BridgeHttpServer {
    private static final Logger LOGGER = McCommandBridge.LOGGER;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int DEFAULT_PORT = 8766;
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_BATCH_COMMANDS = 100;
    private static final long COMMAND_TIMEOUT_MS = 10_000;

    private final String token;
    private final int port;
    private volatile ExecutorService executor;
    private volatile MinecraftServer server;
    private volatile HttpServer httpServer;

    BridgeHttpServer() {
        this.token = BridgeToken.loadOrCreate();
        this.port = parsePort();
    }

    synchronized void start() {
        if (httpServer != null) return;
        try {
            httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            executor = Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "mc-command-bridge-http");
                thread.setDaemon(true);
                return thread;
            });
            httpServer.setExecutor(executor);
            httpServer.createContext("/v1/status", this::handleStatus);
            httpServer.createContext("/v1/command/validate", exchange -> handleCommand(exchange, "validate"));
            httpServer.createContext("/v1/command/run", exchange -> handleCommand(exchange, "run"));
            httpServer.createContext("/v1/command/batch", exchange -> handleCommand(exchange, "batch"));
            httpServer.createContext("/v1/debug/events", this::handleDebugEvents);
            httpServer.createContext("/v1/debug/diagnostics", this::handleDebugDiagnostics);
            httpServer.start();
            LOGGER.info("MC Command Bridge listening on 127.0.0.1:{}", port);
        } catch (Exception error) {
            stop();
            LOGGER.error("Unable to start MC Command Bridge on port {}", port, error);
        }
    }

    synchronized void stop() {
        HttpServer current = httpServer;
        if (current != null) {
            current.stop(0);
            httpServer = null;
        }
        ExecutorService currentExecutor = executor;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
            executor = null;
        }
    }

    void onServerStarted(MinecraftServer started) {
        server = started;
        LOGGER.info("Minecraft server connected: {}", started.getServerVersion());
    }

    void onServerStopping(MinecraftServer stopping) {
        server = null;
        LOGGER.info("Minecraft server disconnected");
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("protocol", "mc-command-bridge/v1");
        response.addProperty("mod", McCommandBridge.MOD_ID);
        response.addProperty("port", port);
        response.addProperty("connected", server != null);
        if (server != null) response.addProperty("game_version", server.getServerVersion());
        JsonArray capabilities = new JsonArray();
        capabilities.add("command.validate");
        capabilities.add("command.run");
        capabilities.add("command.batch");
        capabilities.add("debug.events");
        capabilities.add("debug.diagnostics");
        response.add("capabilities", capabilities);
        send(exchange, 200, response);
    }

    private void handleDebugEvents(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        if (!authorized(exchange)) {
            sendError(exchange, 401, "unauthorized", "missing or invalid bearer token");
            return;
        }
        long since;
        int limit;
        try {
            since = since(exchange);
            limit = pageLimit(exchange);
        } catch (IllegalArgumentException error) {
            sendError(exchange, 400, "invalid_request", error.getMessage());
            return;
        }
        DebugEventStore.Snapshot snapshot = DebugEventStore.snapshotSince(since, limit);
        JsonArray values = new JsonArray();
        ResponsePageBudget page = new ResponsePageBudget(limit, ResponsePageBudget.MAX_JSON_BYTES);
        long returnedNext = snapshot.events().isEmpty() ? snapshot.nextId() : since;
        for (DebugEvent event : snapshot.events()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", event.id());
            value.addProperty("timestamp", event.timestamp());
            value.addProperty("function_id", event.functionId());
            value.addProperty("line", event.line());
            value.addProperty("text", event.text());
            if (event.componentJson() != null) {
                value.add("component", GSON.fromJson(event.componentJson(), JsonElement.class));
            }
            value.addProperty("component_omitted", event.componentOmitted());
            JsonArray stack = new JsonArray();
            event.functionStack().forEach(stack::add);
            value.add("function_stack", stack);
            value.addProperty("truncated", event.truncated());
            if (event.errorCode() != null) value.addProperty("error_code", event.errorCode());
            if (event.error() != null) value.addProperty("error", event.error());
            if (!page.tryAdd(value)) break;
            values.add(value);
            returnedNext = event.id();
        }
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.add("events", values);
        response.addProperty("next_id", returnedNext);
        response.addProperty("latest_id", snapshot.latestId());
        response.addProperty("oldest_id", snapshot.oldestId());
        response.addProperty("dropped", snapshot.dropped());
        response.addProperty("more", returnedNext < snapshot.latestId());
        response.addProperty("returned_count", values.size());
        response.addProperty("response_bytes", page.bytes());
        send(exchange, 200, response);
    }

    private void handleDebugDiagnostics(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        if (!authorized(exchange)) {
            sendError(exchange, 401, "unauthorized", "missing or invalid bearer token");
            return;
        }
        long since;
        int limit;
        try {
            since = since(exchange);
            limit = pageLimit(exchange);
        } catch (IllegalArgumentException error) {
            sendError(exchange, 400, "invalid_request", error.getMessage());
            return;
        }
        DebugDirectiveRegistry.DiagnosticSnapshot snapshot =
                DebugDirectiveRegistry.diagnosticsSnapshotSince(since, limit);
        JsonArray values = new JsonArray();
        ResponsePageBudget page = new ResponsePageBudget(limit, ResponsePageBudget.MAX_JSON_BYTES);
        long returnedNext = snapshot.diagnostics().isEmpty() ? snapshot.nextId() : since;
        for (DebugDiagnostic diagnostic : snapshot.diagnostics()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", diagnostic.id());
            value.addProperty("timestamp", diagnostic.timestamp());
            value.addProperty("phase", diagnostic.phase());
            value.addProperty("code", diagnostic.code());
            value.addProperty("function_id", diagnostic.functionId());
            value.addProperty("line", diagnostic.line());
            value.addProperty("column", diagnostic.column());
            value.addProperty("message", diagnostic.message());
            value.addProperty("source", diagnostic.source());
            if (!page.tryAdd(value)) break;
            values.add(value);
            returnedNext = diagnostic.id();
        }
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.add("diagnostics", values);
        response.addProperty("next_id", returnedNext);
        response.addProperty("latest_id", snapshot.latestId());
        response.addProperty("oldest_id", snapshot.oldestId());
        response.addProperty("dropped", snapshot.dropped());
        response.addProperty("more", returnedNext < snapshot.latestId());
        response.addProperty("returned_count", values.size());
        response.addProperty("response_bytes", page.bytes());
        send(exchange, 200, response);
    }

    private void handleCommand(HttpExchange exchange, String operation) throws IOException {
        if (!method(exchange, "POST")) return;
        if (!authorized(exchange)) {
            sendError(exchange, 401, "unauthorized", "missing or invalid bearer token");
            return;
        }
        JsonObject request;
        try {
            request = GSON.fromJson(readBody(exchange), JsonObject.class);
            if (request == null) throw new IllegalArgumentException("request body must be a JSON object");
        } catch (Exception error) {
            sendError(exchange, 400, "invalid_json", error.getMessage());
            return;
        }

        try {
            JsonObject response = switch (operation) {
                case "validate" -> validate(request);
                case "run" -> run(request);
                case "batch" -> batch(request);
                default -> throw new IllegalArgumentException("unknown operation");
            };
            send(exchange, 200, response);
        } catch (IllegalArgumentException error) {
            sendError(exchange, 400, "invalid_request", error.getMessage());
        } catch (SecurityException error) {
            sendError(exchange, 403, "dangerous_command", error.getMessage());
        } catch (BridgeCommandTimeoutException error) {
            sendTimeout(exchange, error);
        } catch (Exception error) {
            LOGGER.error("Bridge request failed", error);
            sendError(exchange, 500, "bridge_error", error.getMessage());
        }
    }

    private JsonObject validate(JsonObject request) throws Exception {
        String command = command(request);
        return onServerThread((current, control) -> {
            CapturingSource capture = new CapturingSource();
            CommandSourceStack source = source(current, capture);
            ParseResults<CommandSourceStack> parsed = current.getCommands().getDispatcher().parse(
                    Commands.trimOptionalPrefix(command), source);
            JsonObject result = baseResult(current, command);
            result.addProperty("ok", true);
            try {
                Commands.validateParseResults(parsed);
                result.addProperty("valid", true);
            } catch (CommandSyntaxException error) {
                result.addProperty("valid", false);
                result.addProperty("error", error.getMessage());
                result.addProperty("cursor", error.getCursor());
            }
            return result;
        });
    }

    private JsonObject run(JsonObject request) throws Exception {
        String command = command(request);
        guardDangerous(request, command);
        return onServerThread((current, control) -> execute(current, command));
    }

    private JsonObject batch(JsonObject request) throws Exception {
        JsonElement commandsElement = request.get("commands");
        if (commandsElement == null || !commandsElement.isJsonArray()) {
            throw new IllegalArgumentException("commands must be an array");
        }
        JsonArray commands = commandsElement.getAsJsonArray();
        if (commands.isEmpty() || commands.size() > MAX_BATCH_COMMANDS) {
            throw new IllegalArgumentException("commands must contain 1 to " + MAX_BATCH_COMMANDS + " items");
        }
        boolean stopOnError = optionalBoolean(request, "stop_on_error", true);
        List<String> values = new ArrayList<>();
        for (int index = 0; index < commands.size(); index++) {
            String value = commandText(commands.get(index), "commands[" + index + "]");
            guardDangerous(request, value);
            values.add(value);
        }
        return onServerThread((current, control) -> {
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("game_version", current.getServerVersion());
            JsonArray results = new JsonArray();
            for (String value : values) {
                if (control.shouldStop()) break;
                JsonObject item = execute(current, value);
                results.add(item);
                if (!item.get("ok").getAsBoolean()) {
                    result.addProperty("ok", false);
                    if (stopOnError) break;
                }
            }
            result.add("results", results);
            result.addProperty("completed", results.size());
            result.addProperty("requested", values.size());
            return result;
        });
    }

    private JsonObject execute(MinecraftServer current, String command) {
        long started = System.nanoTime();
        CapturingSource capture = new CapturingSource();
        CommandCallbackSummary callback = new CommandCallbackSummary();
        CommandSourceStack source = source(current, capture).withCallback(callback);
        JsonObject response = baseResult(current, command);
        try {
            String normalized = Commands.trimOptionalPrefix(command);
            ParseResults<CommandSourceStack> parsed = current.getCommands().getDispatcher().parse(normalized, source);
            try {
                Commands.validateParseResults(parsed);
                response.addProperty("valid", true);
            } catch (CommandSyntaxException error) {
                response.addProperty("ok", false);
                response.addProperty("valid", false);
                response.addProperty("result_reported", false);
                response.addProperty("error", error.getMessage());
                response.addProperty("cursor", error.getCursor());
                return finishExecutionResponse(response, capture, callback, started);
            }

            ContextChain<CommandSourceStack> chain = ContextChain.tryFlatten(
                    parsed.getContext().build(normalized)
            ).orElseThrow(() -> new IllegalStateException("unable to flatten validated command context"));
            Commands.executeCommandInContext(source, context ->
                    ExecutionContext.queueInitialCommandExecution(
                            context,
                            normalized,
                            chain,
                            source,
                            CommandResultCallback.EMPTY
                    ));
            response.addProperty("result_reported", callback.resultReported());
            if (callback.resultReported()) response.addProperty("result", callback.resultSum());
            response.addProperty("ok", callback.ok());
        } catch (Exception error) {
            response.addProperty("ok", false);
            response.addProperty("error", error.getMessage());
        }
        return finishExecutionResponse(response, capture, callback, started);
    }

    private static JsonObject finishExecutionResponse(
            JsonObject response,
            CapturingSource capture,
            CommandCallbackSummary callback,
            long started
    ) {
        response.addProperty("callback_count", callback.callbackCount());
        response.addProperty("success_count", callback.successCount());
        response.addProperty("failure_count", callback.failureCount());
        JsonArray feedback = new JsonArray();
        capture.messages.messages().forEach(feedback::add);
        response.add("feedback", feedback);
        response.addProperty("feedback_truncated", capture.messages.truncated());
        response.addProperty("duration_ms", (System.nanoTime() - started) / 1_000_000);
        return response;
    }

    private <T> T onServerThread(BiFunction<MinecraftServer, ServerTaskControl, T> work) throws Exception {
        MinecraftServer current = server;
        if (current == null) throw new IllegalStateException("Minecraft server is not connected");
        ServerTaskControl control = new ServerTaskControl(COMMAND_TIMEOUT_MS);
        CompletableFuture<T> future = new CompletableFuture<>();
        current.execute(() -> {
            if (!control.tryStart()) {
                future.completeExceptionally(new TimeoutException("server task cancelled before start"));
                return;
            }
            try {
                future.complete(work.apply(current, control));
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        try {
            return future.get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            control.cancel();
            throw new BridgeCommandTimeoutException(control.requestId(), control.started());
        }
    }

    private static CommandSourceStack source(MinecraftServer current, CapturingSource capture) {
        return current.createCommandSourceStack()
                .withSource(capture)
                .withPermission(PermissionSet.ALL_PERMISSIONS);
    }

    private static JsonObject baseResult(MinecraftServer current, String command) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("command", command);
        result.addProperty("game_version", current.getServerVersion());
        return result;
    }

    private static String command(JsonObject request) {
        return commandText(request.get("command"), "command");
    }

    private static String commandText(JsonElement value, String name) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        String command = value.getAsString().trim();
        if (command.isEmpty() || command.length() > 32_000) {
            throw new IllegalArgumentException(name + " must contain 1 to 32000 characters");
        }
        return command;
    }

    private static void guardDangerous(JsonObject request, String command) {
        String reason = DangerPolicy.reason(command);
        boolean allowed = optionalBoolean(request, "allow_dangerous", false);
        if (reason != null && !allowed) throw new SecurityException(reason);
    }

    private static boolean optionalBoolean(JsonObject request, String name, boolean fallback) {
        if (!request.has(name)) return fallback;
        JsonElement value = request.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String expected = "Bearer " + token;
        return header != null && MessageDigest.isEqual(
                header.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean method(HttpExchange exchange, String expected) throws IOException {
        if (expected.equals(exchange.getRequestMethod())) return true;
        exchange.getResponseHeaders().set("Allow", expected);
        sendError(exchange, 405, "method_not_allowed", "expected " + expected);
        return false;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        long length = contentLength == null ? -1 : Long.parseLong(contentLength);
        if (length > MAX_BODY_BYTES) throw new IOException("request body too large");
        try (InputStream input = exchange.getRequestBody(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) throw new IOException("request body too large");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        body.addProperty("error_code", code);
        body.addProperty("error", message == null ? code : message);
        send(exchange, status, body);
    }

    private static void sendTimeout(HttpExchange exchange, BridgeCommandTimeoutException error) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        body.addProperty("error_code", error.outcomeUnknown()
                ? "timeout_unknown_outcome"
                : "timeout_not_executed");
        body.addProperty("error", error.getMessage());
        body.addProperty("request_id", error.requestId());
        body.addProperty("outcome", error.outcomeUnknown() ? "unknown" : "not_executed");
        body.addProperty("retry_safe", !error.outcomeUnknown());
        send(exchange, 504, body);
    }

    private static long since(HttpExchange exchange) {
        return queryLong(exchange, "since", 0);
    }

    private static int pageLimit(HttpExchange exchange) {
        long value = queryLong(exchange, "limit", ResponsePageBudget.DEFAULT_LIMIT);
        if (value < 1 || value > ResponsePageBudget.MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be an integer from 1 to " + ResponsePageBudget.MAX_LIMIT);
        }
        return (int) value;
    }

    private static long queryLong(HttpExchange exchange, String wanted, long fallback) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) return fallback;
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            if (!key.equals(wanted)) continue;
            String raw = separator < 0 ? "" : pair.substring(separator + 1);
            try {
                long value = Long.parseLong(raw);
                if (value < 0) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(wanted + " must be a non-negative integer");
            }
        }
        return fallback;
    }

    private static int parsePort() {
        try {
            int value = Integer.parseInt(
                    System.getenv().getOrDefault("MC_COMMAND_PORT", String.valueOf(DEFAULT_PORT)));
            return value >= 1 && value <= 65_535 ? value : DEFAULT_PORT;
        } catch (NumberFormatException ignored) {
            return DEFAULT_PORT;
        }
    }

    private static final class CapturingSource implements CommandSource {
        private static final int MAX_MESSAGES = 32;
        private static final int MAX_TOTAL_CHARACTERS = 16_384;
        private static final int MAX_MESSAGE_CHARACTERS = 4_096;
        private final BoundedTextBuffer messages = new BoundedTextBuffer(
                MAX_MESSAGES, MAX_TOTAL_CHARACTERS, MAX_MESSAGE_CHARACTERS);

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() { return true; }

        @Override
        public boolean acceptsFailure() { return true; }

        @Override
        public boolean shouldInformAdmins() { return false; }
    }
}
