package dev.engine_room.flywheel.iris.experimental.mixin;

import dev.engine_room.flywheel.iris.compile.NativeDepth;
import net.irisshaders.iris.pathways.CenterDepthSampler;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.EnumMap;
import java.util.Map;

/**
 * The smoothed centre depth keeps the raw value for a native-depth pack; smoothing is affine.
 */
@Mixin(CenterDepthSampler.class)
abstract class ExperimentalNativeCenterDepthMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/programs/CompositeProgram;<init>(Lnet/irisshaders/iris/samplers/PackSamplers;Ljava/lang/String;Ljava/util/Map;Lnet/irisshaders/iris/targets/ColorAttachmentPlan$Program;Ljava/util/List;)V"),
            index = 2)
    private Map<PatchShaderType, String> flywheel$rawCenterDepth(Map<PatchShaderType, String> sources) {
        if (!NativeDepth.active()) return sources;
        var raw = new EnumMap<>(sources);
        raw.computeIfPresent(PatchShaderType.FRAGMENT,
                (_, fragment) -> fragment.replace("1.0-texture(depth,", "texture(depth,"));
        return raw;
    }
}
