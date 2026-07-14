package dev.underline.mccommand.bridge.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import dev.underline.mccommand.bridge.BridgeToken;
import dev.underline.mccommand.bridge.ResponsePageBudget;
import dev.underline.mccommand.bridge.client.mixin.KeyboardHandlerInvoker;
import dev.underline.mccommand.bridge.client.mixin.MouseHandlerInvoker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class McCommandClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mc-command-bridge-client");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_BODY_BYTES = 64 * 1_024;
    private static final int MAX_JPEG_BYTES = 16 * 1_024 * 1_024;
    private final AtomicReference<InputSequence> sequence = new AtomicReference<>();
    private final AtomicBoolean screenshotInFlight = new AtomicBoolean();
    private final String token = BridgeToken.loadOrCreate();
    private HttpServer httpServer;
    private ExecutorService executor;
    private ExecutorService screenshotExecutor;

    @Override
    public void onInitializeClient() {
        startHttp();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> stop());
    }

    private void startHttp() {
        try {
            int port = clientPort();
            httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            executor = Executors.newFixedThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable, "mc-command-bridge-client-http");
                thread.setDaemon(true);
                return thread;
            });
            screenshotExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mc-command-bridge-jpeg");
                thread.setDaemon(true);
                return thread;
            });
            httpServer.setExecutor(executor);
            httpServer.createContext("/v1/client/status", this::status);
            httpServer.createContext("/v1/client/chat", this::chat);
            httpServer.createContext("/v1/client/input", this::input);
            httpServer.createContext("/v1/client/screenshot", this::screenshot);
            httpServer.start();
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "mc-command-bridge-client-shutdown"));
            LOGGER.info("MC Command client bridge listening on 127.0.0.1:{}", port);
        } catch (Exception error) {
            stop();
            LOGGER.error("Unable to start client bridge", error);
        }
    }

    private static int clientPort() {
        try {
            int value = Integer.parseInt(System.getenv().getOrDefault("MC_COMMAND_CLIENT_PORT", "8767"));
            return value >= 1 && value <= 65_535 ? value : 8_767;
        } catch (NumberFormatException ignored) {
            return 8_767;
        }
    }

    private synchronized void stop() {
        InputSequence currentSequence = sequence.getAndSet(null);
        if (currentSequence != null) currentSequence.cancel("client stopping");

        HttpServer currentServer = httpServer;
        httpServer = null;
        if (currentServer != null) currentServer.stop(0);

        ExecutorService currentExecutor = executor;
        executor = null;
        if (currentExecutor != null) currentExecutor.shutdownNow();

        ExecutorService currentScreenshotExecutor = screenshotExecutor;
        screenshotExecutor = null;
        if (currentScreenshotExecutor != null) currentScreenshotExecutor.shutdownNow();
    }

    private void tick(Minecraft minecraft) {
        minecraft.options.pauseOnLostFocus = false;
        InputSequence current = sequence.get();
        if (!minecraft.isWindowActive()) {
            if (minecraft.mouseHandler.isMouseGrabbed()) minecraft.mouseHandler.releaseMouse();
            if (current != null) current.cancel("window lost focus");
            return;
        }
        if (current != null) current.tick(minecraft);
    }

    private void status(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("protocol", "mc-command-client/v1");
        result.addProperty("connected", minecraft.level != null && minecraft.player != null);
        result.addProperty("focused", minecraft.isWindowActive());
        result.addProperty("mouse_grabbed", minecraft.mouseHandler.isMouseGrabbed());
        if (minecraft.level != null) result.addProperty("game_version", minecraft.getLaunchedVersion());
        JsonArray capabilities = new JsonArray();
        capabilities.add("chat.read");
        capabilities.add("input.sequence");
        capabilities.add("input.gui_cursor");
        capabilities.add("input.double_click");
        capabilities.add("input.drag");
        capabilities.add("screenshot.jpeg");
        capabilities.add("mouse.release_on_focus_loss");
        result.add("capabilities", capabilities);
        send(exchange, 200, result);
    }

    private void chat(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        if (!authorized(exchange)) { sendError(exchange, 401, "unauthorized", "missing or invalid bearer token"); return; }
        Minecraft minecraft = Minecraft.getInstance();
        long since;
        int limit;
        try {
            since = queryLong(exchange.getRequestURI(), "since", 0);
            limit = pageLimit(exchange.getRequestURI());
        } catch (IllegalArgumentException error) {
            sendError(exchange, 400, "invalid_request", error.getMessage());
            return;
        }
        ClientChatEventStore.Snapshot snapshot = ClientChatEventStore.since(since, limit);
        JsonArray output = new JsonArray();
        ResponsePageBudget page = new ResponsePageBudget(limit, ResponsePageBudget.MAX_JSON_BYTES);
        long returnedNext = snapshot.messages().isEmpty() ? snapshot.nextIndex() : since;
        for (ClientChatEventStore.CapturedChatMessage message : snapshot.messages()) {
            JsonObject item = new JsonObject();
            item.addProperty("index", message.index());
            item.addProperty("added_time", message.addedTime());
            item.addProperty("text", message.text());
            item.addProperty("source", message.source());
            item.addProperty("truncated", message.truncated());
            if (!page.tryAdd(item)) break;
            output.add(item);
            returnedNext = message.index();
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("messages", output);
        result.addProperty("next_index", returnedNext);
        result.addProperty("latest_index", snapshot.latestIndex());
        result.addProperty("oldest_index", snapshot.oldestIndex());
        result.addProperty("dropped", snapshot.dropped());
        result.addProperty("more", returnedNext < snapshot.latestIndex());
        result.addProperty("returned_count", output.size());
        result.addProperty("response_bytes", page.bytes());
        send(exchange, 200, result);
    }

    private void input(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) return;
        if (!authorized(exchange)) { sendError(exchange, 401, "unauthorized", "missing or invalid bearer token"); return; }
        try {
            JsonObject request = GSON.fromJson(readBody(exchange), JsonObject.class);
            ClientInputRequest validated = ClientInputRequest.parse(request);
            long timeoutMs = validated.timeoutMs();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            InputSequence candidate = new InputSequence(this, validated.steps(), timeoutMs, future);
            if (!sequence.compareAndSet(null, candidate)) {
                throw new IllegalStateException("another input sequence is running");
            }
            try {
                send(exchange, 200, future.get(timeoutMs + 1_000, TimeUnit.MILLISECONDS));
            } catch (TimeoutException timeout) {
                Minecraft.getInstance().execute(() -> candidate.cancel("input sequence timed out"));
                sendError(exchange, 408, "input_timeout", "input sequence timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Minecraft.getInstance().execute(() -> candidate.cancel("input request was interrupted"));
                sendError(exchange, 503, "input_interrupted", "input request was interrupted");
            }
        } catch (Exception error) {
            sendError(exchange, 400, "input_error", error.getMessage());
        }
    }

    private void screenshot(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) return;
        if (!authorized(exchange)) { sendError(exchange, 401, "unauthorized", "missing or invalid bearer token"); return; }
        if (!screenshotInFlight.compareAndSet(false, true)) {
            sendError(exchange, 429, "screenshot_busy", "another screenshot is being encoded");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> {
                    ExecutorService encoder = screenshotExecutor;
                    if (encoder == null || encoder.isShutdown()) {
                        image.close();
                        screenshotInFlight.set(false);
                        future.completeExceptionally(new IllegalStateException("JPEG encoder is stopping"));
                        return;
                    }
                    try {
                        encoder.execute(() -> saveJpeg(minecraft, image, future));
                    } catch (RuntimeException error) {
                        image.close();
                        screenshotInFlight.set(false);
                        future.completeExceptionally(error);
                    }
                });
            } catch (Throwable error) {
                screenshotInFlight.set(false);
                future.completeExceptionally(error);
            }
        });
        try {
            send(exchange, 200, future.get(30, TimeUnit.SECONDS));
        } catch (TimeoutException error) {
            sendError(exchange, 504, "screenshot_timeout", error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            sendError(exchange, 503, "screenshot_interrupted", "screenshot request was interrupted");
        } catch (Exception error) {
            sendError(exchange, 500, "screenshot_error", error.getMessage());
        }
    }

    private void saveJpeg(Minecraft minecraft, NativeImage image, CompletableFuture<JsonObject> future) {
        Path path = null;
        try (image) {
            Path directory = minecraft.gameDirectory.toPath().resolve("screenshots").resolve("mc-command-mcp");
            Files.createDirectories(directory);
            BufferedImage buffered = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                buffered.setRGB(x, y, ClientPixelColor.rgbFromArgb(image.getPixel(x, y)));
            }
            byte[] bytes = ClientJpegEncoder.encode(buffered, MAX_JPEG_BYTES);
            path = Files.createTempFile(directory, "mc-command-", ".jpg");
            Files.write(path, bytes);
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("path", path.toString());
            result.addProperty("width", image.getWidth());
            result.addProperty("height", image.getHeight());
            result.addProperty("mime_type", "image/jpeg");
            result.addProperty("image_base64", Base64.getEncoder().encodeToString(bytes));
            future.complete(result);
        } catch (Exception error) {
            if (path != null) {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            }
            future.completeExceptionally(error);
        } finally {
            screenshotInFlight.set(false);
        }
    }

    private static final class InputSequence {
        private final McCommandClient owner;
        private final List<ClientInputRequest.Step> steps;
        private final CompletableFuture<JsonObject> future;
        private final long startedAt = System.nanoTime();
        private final long deadline;
        private final ClientInputProgress progress;
        private long until;
        private InputKey held;
        private DragState drag;
        private int doubleClickPhase;
        private long doubleClickIntervalNanos;
        private long doubleClickPressNanos;
        private ClientInputRequest.Step doubleClickStep;

        private InputSequence(
                McCommandClient owner,
                List<ClientInputRequest.Step> steps,
                long timeoutMs,
                CompletableFuture<JsonObject> future
        ) {
            this.owner = owner;
            this.steps = List.copyOf(steps);
            this.progress = new ClientInputProgress(steps.size());
            this.deadline = startedAt + timeoutMs * 1_000_000L;
            this.future = future;
        }

        private synchronized void tick(Minecraft minecraft) {
            long now = System.nanoTime();
            if (now >= deadline) {
                finish(false, "input sequence timed out");
                return;
            }
            if (drag != null) {
                double fraction = until <= drag.startedAt()
                        ? 1
                        : Math.min(1, (double) (now - drag.startedAt()) / (until - drag.startedAt()));
                moveCursor(minecraft,
                        drag.fromX() + (drag.toX() - drag.fromX()) * fraction,
                        drag.fromY() + (drag.toY() - drag.fromY()) * fraction);
                if (now < until) return;
                releaseHeld();
                drag = null;
                progress.completeTimedStep();
            }
            if (doubleClickPhase != 0) {
                if (now < until) return;
                if (doubleClickPhase == 1) {
                    releaseHeld();
                    doubleClickPhase = 2;
                    until = now + doubleClickIntervalNanos;
                    return;
                }
                if (doubleClickPhase == 2) {
                    InputKey key = resolveInput(minecraft, "mouse", doubleClickStep.name());
                    if (key == null) {
                        finish(false, "mouse mapping became unavailable for '" + doubleClickStep.name() + "'");
                        return;
                    }
                    key.set(true);
                    held = key;
                    doubleClickPhase = 3;
                    until = now + doubleClickPressNanos;
                    return;
                }
                releaseHeld();
                doubleClickPhase = 0;
                doubleClickStep = null;
                progress.completeTimedStep();
            }
            if (until != 0 && now < until) return;
            if (progress.activeTimedStep()) {
                releaseHeld();
                progress.completeTimedStep();
            }
            if (!progress.hasNext()) {
                finish(true, null);
                return;
            }
            ClientInputRequest.Step step = steps.get(progress.startNext());
            if (step.type().equals("wait")) {
                startDuration(step.durationMs(), now);
            } else if (step.type().equals("look")) {
                if (minecraft.player != null) minecraft.player.turn(
                        ClientLookDelta.turnUnitsForDegrees(step.yawDelta()),
                        ClientLookDelta.turnUnitsForDegrees(step.pitchDelta())
                );
                progress.completeImmediateStep();
            } else if (step.type().equals("cursor")) {
                if (!canUseCursor(minecraft)) {
                    finish(false, "cursor movement requires an open GUI with the mouse released");
                    return;
                }
                if (step.action().equals("move")) {
                    CursorPoint point = absolutePoint(minecraft.getWindow(), step.x(), step.y(), step.coordinateSpace());
                    moveCursor(minecraft, point.x(), point.y());
                } else {
                    moveCursor(minecraft,
                            minecraft.mouseHandler.xpos() + step.dx(),
                            minecraft.mouseHandler.ypos() + step.dy());
                }
                progress.completeImmediateStep();
            } else if (step.type().equals("drag")) {
                if (!canUseCursor(minecraft)) {
                    finish(false, "drag requires an open GUI with the mouse released");
                    return;
                }
                CursorPoint from = absolutePoint(
                        minecraft.getWindow(), step.fromX(), step.fromY(), step.coordinateSpace());
                CursorPoint to = absolutePoint(
                        minecraft.getWindow(), step.toX(), step.toY(), step.coordinateSpace());
                moveCursor(minecraft, from.x(), from.y());
                InputKey key = resolveInput(minecraft, "mouse", step.name());
                if (key == null) {
                    finish(false, "mouse mapping became unavailable for '" + step.name() + "'");
                    return;
                }
                key.set(true);
                held = key;
                if (step.durationMs() == 0) {
                    moveCursor(minecraft, to.x(), to.y());
                    releaseHeld();
                    progress.completeImmediateStep();
                } else {
                    progress.beginTimedStep();
                    drag = new DragState(now, from.x(), from.y(), to.x(), to.y());
                    until = now + step.durationMs() * 1_000_000L;
                }
            } else {
                InputKey key = resolveInput(minecraft, step.type(), step.name());
                if (key == null) {
                    finish(false, "input mapping became unavailable for '" + step.name() + "'");
                    return;
                }
                if (step.action().equals("release")) {
                    key.set(false);
                    progress.completeImmediateStep();
                } else if (step.action().equals("double_click")) {
                    key.set(true);
                    held = key;
                    progress.beginTimedStep();
                    doubleClickPhase = 1;
                    doubleClickStep = step;
                    doubleClickPressNanos = step.durationMs() * 1_000_000L;
                    doubleClickIntervalNanos = step.intervalMs() * 1_000_000L;
                    until = now + doubleClickPressNanos;
                } else {
                    key.set(true);
                    held = key;
                    startDuration(step.durationMs(), now);
                }
            }
        }

        private void startDuration(long durationMs, long now) {
            if (durationMs == 0) {
                releaseHeld();
                progress.completeImmediateStep();
                return;
            }
            progress.beginTimedStep();
            until = now + durationMs * 1_000_000L;
        }

        private void releaseHeld() { if (held != null) { held.set(false); held = null; } until = 0; }

        private synchronized void cancel(String message) {
            finish(false, message);
        }

        private void finish(boolean successful, String error) {
            if (future.isDone()) return;
            releaseHeld();
            drag = null;
            doubleClickPhase = 0;
            doubleClickStep = null;
            owner.sequence.compareAndSet(this, null);
            JsonObject result = new JsonObject();
            result.addProperty("ok", successful);
            result.addProperty("completed_steps", progress.completedSteps());
            result.addProperty("requested_steps", steps.size());
            result.addProperty("duration_ms", (System.nanoTime() - startedAt) / 1_000_000L);
            if (error != null) result.addProperty("error", error);
            future.complete(result);
        }
    }

    private interface InputKey { void set(boolean down); }
    private static InputKey resolveInput(Minecraft minecraft, String type, String name) {
        if (type.equals("mouse")) {
            int button = switch (name.toLowerCase(Locale.ROOT)) { case "left" -> InputConstants.MOUSE_BUTTON_LEFT; case "right" -> InputConstants.MOUSE_BUTTON_RIGHT; case "middle" -> InputConstants.MOUSE_BUTTON_MIDDLE; default -> -1; };
            if (button < 0) return null;
            return down -> ((MouseHandlerInvoker) minecraft.mouseHandler).mcCommandInvokeOnButton(
                    minecraft.getWindow().handle(), new MouseButtonInfo(button, 0),
                    down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE);
        }
        KeyMapping mapping = switch (name.toLowerCase(Locale.ROOT)) {
            case "forward", "up", "w" -> minecraft.options.keyUp;
            case "back", "down", "s" -> minecraft.options.keyDown;
            case "left", "a" -> minecraft.options.keyLeft;
            case "right", "d" -> minecraft.options.keyRight;
            case "jump", "space" -> minecraft.options.keyJump;
            case "sneak", "shift" -> minecraft.options.keyShift;
            case "sprint", "ctrl", "control" -> minecraft.options.keySprint;
            case "use", "right_click" -> minecraft.options.keyUse;
            case "attack", "left_click" -> minecraft.options.keyAttack;
            default -> null;
        };
        if (mapping != null) return mapping::setDown;
        if (name.length() == 1) {
            int keyCode = Character.toUpperCase(name.charAt(0));
            return down -> ((KeyboardHandlerInvoker) minecraft.keyboardHandler).mcCommandInvokeKeyPress(
                    minecraft.getWindow().handle(), down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE,
                    new KeyEvent(keyCode, 0, 0));
        }
        return null;
    }

    private static boolean canUseCursor(Minecraft minecraft) {
        return minecraft.gui.screen() != null && !minecraft.mouseHandler.isMouseGrabbed();
    }

    private static CursorPoint absolutePoint(Window window, double x, double y, String coordinateSpace) {
        if (coordinateSpace.equals("normalized")) {
            return new CursorPoint(x * Math.max(0, window.getWidth() - 1),
                    y * Math.max(0, window.getHeight() - 1));
        }
        return new CursorPoint(x, y);
    }

    private static void moveCursor(Minecraft minecraft, double x, double y) {
        Window window = minecraft.getWindow();
        double clampedX = Math.max(0, Math.min(Math.max(0, window.getWidth() - 1), x));
        double clampedY = Math.max(0, Math.min(Math.max(0, window.getHeight() - 1), y));
        GLFW.glfwSetCursorPos(window.handle(), clampedX, clampedY);
        ((MouseHandlerInvoker) minecraft.mouseHandler)
                .mcCommandInvokeOnMove(window.handle(), clampedX, clampedY);
    }

    private record CursorPoint(double x, double y) { }
    private record DragState(long startedAt, double fromX, double fromY, double toX, double toY) { }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String expected = "Bearer " + token;
        return header != null && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
    private static int pageLimit(URI uri) {
        long value = queryLong(uri, "limit", ResponsePageBudget.DEFAULT_LIMIT);
        if (value < 1 || value > ResponsePageBudget.MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be an integer from 1 to " + ResponsePageBudget.MAX_LIMIT);
        }
        return (int) value;
    }
    private static long queryLong(URI uri, String key, long fallback) { String query = uri.getRawQuery(); if (query == null) return fallback; for (String part : query.split("&")) { String[] pair = part.split("=", 2); if (pair.length == 2 && pair[0].equals(key)) try { long value = Long.parseLong(pair[1]); if (value < 0) throw new NumberFormatException(); return value; } catch (NumberFormatException error) { throw new IllegalArgumentException(key + " must be a non-negative integer"); } } return fallback; }
    private static String readBody(HttpExchange exchange) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > MAX_BODY_BYTES) throw new IOException("request body too large");
            } catch (NumberFormatException error) {
                throw new IOException("invalid Content-Length", error);
            }
        }
        try (InputStream input = exchange.getRequestBody(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4_096];
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
    private static boolean method(HttpExchange exchange, String expected) throws IOException { if (expected.equals(exchange.getRequestMethod())) return true; sendError(exchange, 405, "method_not_allowed", "expected " + expected); return false; }
    private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException { byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); exchange.sendResponseHeaders(status, bytes.length); try (var output = exchange.getResponseBody()) { output.write(bytes); } }
    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException { JsonObject body = new JsonObject(); body.addProperty("ok", false); body.addProperty("error_code", code); body.addProperty("error", message == null ? code : message); send(exchange, status, body); }
}
