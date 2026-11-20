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
        if (Mapper.getBiomeId(state) == ZoneVoxelOverlay.SYNTHETIC_BIOME_ID) {
            // The normal packer strips biome data for models without biome tinting.
            // Reinsert the marker in bit 8 of the 9-bit biome field for every model.
            cir.setReturnValue(cir.getReturnValue() | (1L << 54));
        }
    }
}
