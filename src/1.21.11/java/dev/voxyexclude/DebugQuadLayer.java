package dev.voxyexclude;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;

/** Version-specific access to Minecraft's debug quad render layer. */
final class DebugQuadLayer {
    private DebugQuadLayer() {}

    static RenderLayer get() {
        return RenderLayers.debugQuads();
    }
}
