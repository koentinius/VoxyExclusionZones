package dev.voxyexclude;

import net.fabricmc.api.ClientModInitializer;

public final class VoxyExcludeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        try {
            ZoneManager.load();
            ZoneCommands.register();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Voxy Exclusion Zones configuration", e);
        }
    }
}
