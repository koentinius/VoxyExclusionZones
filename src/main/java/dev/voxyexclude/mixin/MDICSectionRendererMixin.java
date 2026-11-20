package dev.voxyexclude.mixin;

import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Temporary diagnostic: bypass only the depth test used while Voxy rasterizes
 * section proxy boxes into its visibility buffer. The real terrain pass keeps
 * its normal depth testing.
 */
@Mixin(value = MDICSectionRenderer.class, remap = false)
public abstract class MDICSectionRendererMixin {
    @Inject(
            method = "buildDrawCalls",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/GL43;glDrawElementsIndirect(IIJ)V",
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private void voxyexclude$disableDepthTestForVisibilityDiagnostic(
            MDICViewport viewport,
            CallbackInfo ci
    ) {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }
}
