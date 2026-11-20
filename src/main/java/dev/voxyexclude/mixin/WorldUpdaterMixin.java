package dev.voxyexclude.mixin;

import dev.voxyexclude.ZoneVoxelOverlay;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldUpdater.class, remap = false)
public abstract class WorldUpdaterMixin {
    @Inject(method = "insertUpdate", at = @At("HEAD"), remap = false)
    private static void voxyexclude$overlayIncomingSection(
            WorldEngine engine, VoxelizedSection section, CallbackInfo ci
    ) {
        ZoneVoxelOverlay.lockUpdate(section);
        ZoneVoxelOverlay.beginIngest(engine, section);
    }

    @Inject(method = "insertUpdate", at = @At("RETURN"), remap = false)
    private static void voxyexclude$finishIncomingSection(
            WorldEngine engine, VoxelizedSection section, CallbackInfo ci
    ) {
        ZoneVoxelOverlay.endIngest();
        ZoneVoxelOverlay.diagnoseParentLods(engine, section);
        ZoneVoxelOverlay.unlockUpdate();
    }
}
