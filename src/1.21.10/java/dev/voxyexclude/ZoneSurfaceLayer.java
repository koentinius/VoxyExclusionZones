package dev.voxyexclude;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

/** Version-specific textured layer factory for the block atlas. */
final class ZoneSurfaceLayer {
    private ZoneSurfaceLayer() {}
    static RenderLayer get(Identifier atlas) {
        return RenderLayer.getEntityTranslucent(atlas);
    }
}
