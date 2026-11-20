package dev.voxyexclude.mixin;

import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VoxelIngestService.class, remap = false)
public interface VoxelIngestServiceAccessor {
    @Accessor(value = "service", remap = false)
    Service voxyexclude$getService();
}
