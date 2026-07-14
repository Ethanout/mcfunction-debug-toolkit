package dev.underline.mccommand.bridge;

import dev.underline.mccommand.bridge.debug.DebugInternalCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McCommandBridge implements ModInitializer {
    public static final String MOD_ID = "mc-command-bridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private BridgeHttpServer bridge;

    @Override
    public void onInitialize() {
        DebugInternalCommand.register();
        bridge = new BridgeHttpServer();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            bridge.onServerStarted(server);
            bridge.start();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            bridge.onServerStopping(server);
            bridge.stop();
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> bridge.stop(), "mc-command-bridge-shutdown"));
        LOGGER.info("MC Command Bridge initialized");
    }
}
