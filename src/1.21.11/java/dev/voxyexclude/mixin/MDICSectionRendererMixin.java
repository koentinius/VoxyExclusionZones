package dev.voxyexclude.mixin;

import dev.voxyexclude.ExclusionSsbo;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MDICSectionRenderer.class, remap = false)
public abstract class MDICSectionRendererMixin {
    @Unique
    private ExclusionSsbo voxyexclude$zoneSsbo;

    @Unique
    private ExclusionSsbo.BindingState voxyexclude$bindingState;

    @Unique
    private void voxyexclude$bindZones() {
        if (this.voxyexclude$zoneSsbo == null) {
            this.voxyexclude$zoneSsbo = new ExclusionSsbo();
        }
        this.voxyexclude$bindingState = this.voxyexclude$zoneSsbo.bind();
    }

    @Unique
    private void voxyexclude$restoreZones() {
        if (this.voxyexclude$zoneSsbo != null && this.voxyexclude$bindingState != null) {
            this.voxyexclude$zoneSsbo.restore(this.voxyexclude$bindingState);
            this.voxyexclude$bindingState = null;
        }
    }

    @Inject(
            method = "renderTerrain",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/cortex/voxy/client/core/gl/shader/Shader;bind()V",
                    shift = At.Shift.AFTER
            ),
            remap = false
    )
    private void voxyexclude$bindOpaqueAndTemporal(
            MDICViewport viewport, long indirectOffset, long drawCountOffset, int maxDrawCount,
            CallbackInfo ci
    ) {
        this.voxyexclude$bindZones();
    }

    @Inject(method = "renderTerrain", at = @At("RETURN"), remap = false)
    private void voxyexclude$restoreOpaqueAndTemporal(
            MDICViewport viewport, long indirectOffset, long drawCountOffset, int maxDrawCount,
            CallbackInfo ci
    ) {
        this.voxyexclude$restoreZones();
    }

    @Inject(
            method = "renderTranslucent",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/cortex/voxy/client/core/gl/shader/Shader;bind()V",
                    shift = At.Shift.AFTER
            ),
            remap = false
    )
    private void voxyexclude$bindTranslucent(MDICViewport viewport, CallbackInfo ci) {
        this.voxyexclude$bindZones();
    }

    @Inject(method = "renderTranslucent", at = @At("RETURN"), remap = false)
    private void voxyexclude$restoreTranslucent(MDICViewport viewport, CallbackInfo ci) {
        this.voxyexclude$restoreZones();
    }

    @Inject(method = "free", at = @At("HEAD"), remap = false)
    private void voxyexclude$freeZoneBuffer(CallbackInfo ci) {
        if (this.voxyexclude$zoneSsbo != null) {
            this.voxyexclude$zoneSsbo.free();
            this.voxyexclude$zoneSsbo = null;
            this.voxyexclude$bindingState = null;
        }
    }
}
