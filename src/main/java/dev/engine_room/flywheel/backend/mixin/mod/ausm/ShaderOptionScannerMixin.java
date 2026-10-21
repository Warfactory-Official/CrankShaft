package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import com.luna.ausm.api.pipeline.pack.ShaderOption;
import com.luna.ausm.impl.pipeline.pack.ShaderOptionScanner;
import com.luna.ausm.impl.pipeline.pack.ShaderPack;
import dev.engine_room.flywheel.backend.engine.shaderpack.AusmShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.Set;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.pack.ShaderOptionScanner", remap = false)
public abstract class ShaderOptionScannerMixin {
    // shader.h is the final discovery call, before overrides are applied; guest-only options must exist by that point.
    @Redirect(method = "scan", at = @At(value = "INVOKE",
            target = "Lcom/luna/ausm/impl/pipeline/pack/ShaderOptionScanner;scanShaderFile(Lcom/luna/ausm/impl/pipeline/pack/ShaderPack;Ljava/lang/String;Ljava/util/Set;Ljava/util/Set;Ljava/util/Map;Ljava/util/Set;)V",
            ordinal = 4), require = 1)
    private static void flw$guestOptions(ShaderPack pack, String path, Set<String> sliders, Set<String> propertyOptions,
                                         Map<String, ShaderOption> options, Set<String> visited) {
        ShaderOptionScanner.scanShaderFile(pack, path, sliders, propertyOptions, options, visited);
        AusmShaderPack.scanGuestOptions(pack, sliders, propertyOptions, options, visited);
    }
}
