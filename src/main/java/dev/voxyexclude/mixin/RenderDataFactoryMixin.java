package dev.voxyexclude.mixin;

import dev.voxyexclude.ZoneVoxelOverlay;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RenderDataFactory.class, remap = false)
public abstract class RenderDataFactoryMixin {
    @Inject(method = "packPartialQuadData", at = @At("RETURN"), cancellable = true, remap = false)
    private static void voxyexclude$preserveSyntheticMarker(
            int modelId, long state, long metadata, CallbackInfoReturnable<Long> cir
    ) {
        if ((state & ZoneVoxelOverlay.SYNTHETIC_VOXEL_MARKER) != 0) {
            // The normal packer strips biome data for models without biome tinting.
            // Bit 42 is unused in Voxy's packed quad format (model ID ends at bit 41,
            // biome tint begins at bit 46). Keep the marker out of biome tint data.
            cir.setReturnValue(cir.getReturnValue() | (1L << 42));
        }
    }
}
