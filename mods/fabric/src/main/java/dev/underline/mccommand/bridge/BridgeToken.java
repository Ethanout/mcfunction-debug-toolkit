package dev.underline.mccommand.bridge;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;

public final class BridgeToken {
    private BridgeToken() {
    }

    public static synchronized String loadOrCreate() {
        String environment = System.getenv("MC_COMMAND_TOKEN");
        if (environment != null && !environment.isBlank()) return environment.trim();

        Path path = FabricLoader.getInstance().getConfigDir().resolve("mc-command-mcp.token");
        try {
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                String existing = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (!existing.isBlank()) return existing;
            }

            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Files.writeString(path, generated + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            McCommandBridge.LOGGER.info("Generated bridge token at {}", path);
            return generated;
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load or create bridge token at " + path, error);
        }
    }
}
