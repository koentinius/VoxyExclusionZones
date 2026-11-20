package dev.voxyexclude;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

/** Version-specific textured layer factory for the block atlas. */
final class ZoneSurfaceLayer {
    private ZoneSurfaceLayer() {}
    static RenderLayer get(Identifier atlas, boolean translucent) {
        return translucent ? RenderLayer.getEntityTranslucent(atlas) : RenderLayer.getEntitySolid(atlas);
    }
}
