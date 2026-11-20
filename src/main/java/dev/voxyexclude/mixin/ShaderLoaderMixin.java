package dev.voxyexclude.mixin;

import dev.voxyexclude.ExclusionSsbo;
import dev.voxyexclude.ShaderPatcher;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShaderLoader.class, remap = false)
public abstract class ShaderLoaderMixin {
    @Inject(method = "parse", at = @At("RETURN"), cancellable = true, remap = false)
    private static void voxyexclude$patchTerrainShaders(
            String id,
            CallbackInfoReturnable<String> cir
    ) {
        if (ShaderPatcher.FRAGMENT_SHADER.equals(id)) {
            ExclusionSsbo.assertBindingSupported();
        }
        cir.setReturnValue(ShaderPatcher.patch(id, cir.getReturnValue()));
    }
}
