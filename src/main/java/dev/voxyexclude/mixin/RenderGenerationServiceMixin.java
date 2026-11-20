package dev.voxyexclude.mixin;

import dev.voxyexclude.ZoneVoxelOverlay;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.common.world.WorldSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents Voxy from meshing a section while the exclusion overlay is mutating
 * that section, and keeps the lock until the generated result has been queued.
 */
@Mixin(value = RenderGenerationService.class, remap = false)
public abstract class RenderGenerationServiceMixin {
    @Redirect(
            method = "processJob",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/cortex/voxy/client/core/rendering/building/RenderDataFactory;generateMesh(Lme/cortex/voxy/common/world/WorldSection;)Lme/cortex/voxy/client/core/rendering/building/BuiltSection;"
            ),
            remap = false
    )
    private BuiltSection voxyexclude$lockSectionDuringMeshBuild(
            RenderDataFactory factory, WorldSection section
    ) {
        ZoneVoxelOverlay.lockMeshBuild(section);
        return factory.generateMesh(section);
    }

    @Inject(method = "processJob", at = @At("RETURN"), remap = false)
    private void voxyexclude$unlockAfterMeshResultSubmission(
            RenderDataFactory factory, IntOpenHashSet seenMissedIds, CallbackInfo ci
    ) {
        ZoneVoxelOverlay.unlockMeshBuild();
    }
}
