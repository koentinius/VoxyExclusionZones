package dev.voxyexclude;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class VoxyExcludeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        try {
            ZoneManager.load();
            ZoneCommands.register();
            ZoneConfigLifecycle.register();
            ClientTickEvents.END_CLIENT_TICK.register(ZoneVoxelOverlay::refreshSnapshot);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Voxy Exclusion Zones configuration", e);
        }
    }
}
