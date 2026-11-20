package dev.voxyexclude.mixin;

import dev.voxyexclude.ZoneVoxelOverlay;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    @Inject(
            method = "acquire(IIII)Lme/cortex/voxy/common/world/WorldSection;",
            at = @At("RETURN"),
            remap = false
    )
    private void voxyexclude$overlayAcquiredSection(
            int level, int x, int y, int z, CallbackInfoReturnable<WorldSection> cir
    ) {
        ZoneVoxelOverlay.onSectionAcquired((WorldEngine) (Object) this, cir.getReturnValue());
    }

    @Inject(
            method = "acquire(J)Lme/cortex/voxy/common/world/WorldSection;",
            at = @At("RETURN"),
            remap = false
    )
    private void voxyexclude$overlayAcquiredSectionByKey(
            long key, CallbackInfoReturnable<WorldSection> cir
    ) {
        ZoneVoxelOverlay.onSectionAcquired((WorldEngine) (Object) this, cir.getReturnValue());
    }

    @Inject(
            method = "acquireIfExists(IIII)Lme/cortex/voxy/common/world/WorldSection;",
            at = @At("RETURN"),
            remap = false
    )
    private void voxyexclude$overlayExistingSection(
            int level, int x, int y, int z, CallbackInfoReturnable<WorldSection> cir
    ) {
        ZoneVoxelOverlay.onSectionAcquired((WorldEngine) (Object) this, cir.getReturnValue());
    }

    @Inject(
            method = "acquireIfExists(J)Lme/cortex/voxy/common/world/WorldSection;",
            at = @At("RETURN"),
            remap = false
    )
    private void voxyexclude$overlayExistingSectionByKey(
            long key, CallbackInfoReturnable<WorldSection> cir
    ) {
        ZoneVoxelOverlay.onSectionAcquired((WorldEngine) (Object) this, cir.getReturnValue());
    }
}
