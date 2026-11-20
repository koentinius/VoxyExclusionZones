package dev.voxyexclude;

import net.minecraft.client.render.RenderLayer;

/** Version-specific access to Minecraft's debug quad render layer. */
final class DebugQuadLayer {
    private DebugQuadLayer() {}

    static RenderLayer get() {
        return RenderLayer.getDebugQuads();
    }
}
