package dev.voxyexclude.mixin;

import dev.voxyexclude.ZoneVoxelOverlay;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps synthetic replacement voxels represented when Voxy builds parent LODs.
 * WorldUpdater uses Mipper directly for those parent levels, outside the
 * addon's custom VoxelizedSection mip generation.
 */
@Mixin(value = Mipper.class, remap = false)
public abstract class MipperMixin {
    @Inject(method = "mip", at = @At("HEAD"), cancellable = true, remap = false)
    private static void voxyexclude$preserveSyntheticVoxels(
            long i000, long i100, long i001, long i101,
            long i010, long i110, long i011, long i111,
            Mapper mapper, CallbackInfoReturnable<Long> cir
    ) {
        if ((i111 & ZoneVoxelOverlay.SYNTHETIC_VOXEL_MARKER) != 0) {
            cir.setReturnValue(i111);
        } else if ((i110 & ZoneVoxelOverlay.SYNTHETIC_VOXEL_MARKER) != 0) {
            cir.setReturnValue(i110);
        } else if ((i011 & ZoneVoxelOverlay.SYNTHETIC_VOXEL_MARKER) != 0) {
            cir.setReturnValue(i011);
        } else if ((i010 & ZoneVoxelOverlay.SYNTHETIC_VOXEL_MARKER) != 0) {
            cir.setReturnValue(i010);
        } else if ((i101 & ZoneVoxelOverlay.SYNTHETIC_VOXEL_MARKER) != 0) {
            cir.setReturnValue(i101);
        } else if ((i100 & ZoneVoxelOverlay.SYNTHETIC_VOXEL_MARKER) != 0) {
            cir.setReturnValue(i100);
        } else if ((i001 & ZoneVoxelOverlay.SYNTHETIC_VOXEL_MARKER) != 0) {
            cir.setReturnValue(i001);
        } else if ((i000 & ZoneVoxelOverlay.SYNTHETIC_VOXEL_MARKER) != 0) {
            cir.setReturnValue(i000);
        }
    }
}
