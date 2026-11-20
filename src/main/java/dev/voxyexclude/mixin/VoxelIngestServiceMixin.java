package dev.voxyexclude.mixin;

import dev.voxyexclude.ZoneVoxelOverlay;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VoxelIngestService.class, remap = false)
public abstract class VoxelIngestServiceMixin {
    @Inject(method = "processJob", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxyexclude$runPreVoxelizedWork(CallbackInfo ci) {
        if (ZoneVoxelOverlay.runQueuedIngestWork((VoxelIngestService) (Object) this)) {
            ci.cancel();
        }
    }
}
