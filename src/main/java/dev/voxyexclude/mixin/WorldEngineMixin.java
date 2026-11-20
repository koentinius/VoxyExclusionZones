package dev.voxyexclude.mixin;

import dev.voxyexclude.ZoneVoxelOverlay;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = WorldEngine.class, remap = false)
public abstract class WorldEngineMixin {
    @ModifyVariable(
            method = "markDirty(Lme/cortex/voxy/common/world/WorldSection;II)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            remap = false
    )
    private int voxyexclude$doNotPersistZoneVoxels(int changeState) {
        return ZoneVoxelOverlay.shouldSuppressSave()
                ? changeState | WorldEngine.UPDATE_TYPE_DONT_SAVE
                : changeState;
    }
}
