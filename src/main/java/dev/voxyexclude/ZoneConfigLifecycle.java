package dev.voxyexclude;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Selects an independent zone file for each multiplayer server or single-player save. */
public final class ZoneConfigLifecycle {
    private ZoneConfigLifecycle() {}

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
            client.execute(() -> activate(client)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            client.execute(ZoneManager::unload));
    }

    private static void activate(MinecraftClient client) {
        try {
            Path root = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("voxy-exclusion-zones");
            Path target;
            if (client.isInSingleplayer() && client.getServer() != null) {
                // Persistent save path, not the mutable world display name.
                String identity = client.getServer().getSavePath(WorldSavePath.ROOT)
                    .toAbsolutePath().normalize().toString();
                target = root.resolve("worlds").resolve(hash(identity) + ".json");
            } else if (client.getCurrentServerEntry() != null) {
                // Address includes the configured host and port where supplied by Minecraft.
                String address = client.getCurrentServerEntry().address.trim().toLowerCase(Locale.ROOT);
                target = root.resolve("servers").resolve(hash(address) + ".json");
            } else {
                target = root.resolve("default.json");
            }
            ZoneManager.switchStorage(target);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to activate this world's Voxy Exclusion Zones config", e);
        }
    }

    private static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
