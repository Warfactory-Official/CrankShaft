package dev.engine_room.flywheel.iris.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.iris.compile.ContractProperties;
import dev.engine_room.flywheel.iris.compile.ContractShaderPack;
import dev.engine_room.flywheel.iris.compile.patches.ContractPatches;
import dev.engine_room.flywheel.iris.compile.patches.DeferredOitProfile;
import dev.engine_room.flywheel.iris.compile.patches.IterationImageAbi;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@Mixin(ShaderPack.class)
abstract class ShaderPackMixin implements ContractShaderPack {
    @Shadow
    @Final
    private ShaderPackOptions shaderPackOptions;

    @Unique
    private ContractProperties flywheel$contractProperties;

    @Unique
    private ContractPatches.Plan flywheel$patchPlan;

    @Unique
    private boolean flywheel$forwardOit;

    @Unique
    private @Nullable DeferredOitProfile flywheel$deferredOit;

    @Unique
    private @Nullable DeferredOitProfile flywheel$forwardProfile;

    @Unique
    private boolean flywheel$deferredEmissive;

    @Unique
    private boolean flywheel$deferredTranslucent;

    @Unique
    private boolean flywheel$emissiveLight;

    @Unique
    private boolean flywheel$iterationImageAbi;

    @Unique
    private float flywheel$renderScale = 1;

    @Unique
    private boolean flywheel$nativeDepth;

    // Root not retained past construction; defines = program-source preprocessing set.
    @Inject(method = "<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V",
            at = @At("RETURN"))
    private void flywheel$loadContractProperties(Path _, Map<String, String> _,
                                                 ImmutableList<StringPair> _, boolean _,
                                                 CallbackInfo _,
                                                 @Local(name = "finalEnvironmentDefines1") Iterable<StringPair> defines) {
        flywheel$contractProperties = ContractProperties.parse(flywheel$patchPlan.properties(), shaderPackOptions,
                defines);
        flywheel$patchPlan = null;
    }

    // Compat with Super Resolution: its pack interface squeezes the world into the lower-left of full-size targets.
    @Inject(method = "<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V",
            at = @At("RETURN"))
    private void flywheel$readRenderScale(Path _, Map<String, String> _,
                                          ImmutableList<StringPair> environmentDefines, boolean _,
                                          CallbackInfo _) {
        for (StringPair define : environmentDefines) {
            if (define.key().equals("SR_RENDER_SCALE_FACTOR")) flywheel$renderScale = Float.parseFloat(define.value());
        }
    }

    @WrapOperation(method = "<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V",
            at = @At(value = "NEW",
                    target = "(Ljava/nio/file/Path;Lcom/google/common/collect/ImmutableList;Z)Lnet/irisshaders/iris/shaderpack/include/IncludeGraph;"))
    private IncludeGraph flywheel$patchContract(Path root, ImmutableList<AbsolutePackPath> starts, boolean isZip,
                                                Operation<IncludeGraph> original) {
        try {
            flywheel$patchPlan = ContractPatches.begin(root, starts);
            flywheel$forwardOit = flywheel$patchPlan.forwardOit();
            flywheel$deferredOit = flywheel$patchPlan.deferred();
            flywheel$forwardProfile = flywheel$patchPlan.forward();
            flywheel$deferredEmissive = flywheel$patchPlan.deferredEmissive();
            flywheel$deferredTranslucent = flywheel$patchPlan.deferredTranslucent();
            flywheel$emissiveLight = flywheel$patchPlan.emissiveLight();
            flywheel$iterationImageAbi = flywheel$patchPlan.iterationImageAbi();
            flywheel$nativeDepth = flywheel$patchPlan.nativeDepth();
            return original.call(root, flywheel$patchPlan.starts(), isZip);
        } finally {
            ContractPatches.end();
        }
    }

    @Override
    public ContractProperties flywheel$contractProperties() {
        return flywheel$contractProperties;
    }

    @Override
    public boolean flywheel$forwardOit() {
        return flywheel$forwardOit;
    }

    @WrapOperation(method = "<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/ShaderPack;loadProperties(Ljava/nio/file/Path;Ljava/lang/String;)Ljava/util/Optional;"))
    private Optional<String> flywheel$adapterProperties(Path root, String name, Operation<Optional<String>> original) {
        if (name.equals("shaders.properties") && flywheel$patchPlan.shadersProperties() != null) {
            return Optional.of(flywheel$patchPlan.shadersProperties());
        }
        return original.call(root, name);
    }

    @Override
    public @Nullable DeferredOitProfile flywheel$deferredOit() {
        return flywheel$deferredOit;
    }

    @Override
    public @Nullable DeferredOitProfile flywheel$forwardProfile() {
        return flywheel$forwardProfile;
    }

    // Adapt both base and lazy dimension ProgramSets while leaving the provider's final constructor write intact.
    @ModifyExpressionValue(method = {"<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V",
            "lambda$getProgramSet$0"},
            at = @At(value = "FIELD", target = "Lnet/irisshaders/iris/shaderpack/ShaderPack;sourceProvider:Ljava/util/function/Function;",
                    opcode = Opcodes.GETFIELD))
    private Function<AbsolutePackPath, String> flywheel$adaptIterationImages(
            Function<AbsolutePackPath, String> sourceProvider) {
        if (!flywheel$iterationImageAbi) return sourceProvider;
        return path -> {
            String source = sourceProvider.apply(path);
            // This provider also serves pack metadata and Voxy libraries; included GLSL is adapted in its full stage.
            String name = path.getPathString();
            return switch (name.substring(name.lastIndexOf('.') + 1)) {
                case "vsh", "fsh", "gsh", "tcs", "tes", "csh" ->
                        source == null ? null : IterationImageAbi.source(source);
                default -> source;
            };
        };
    }

    @Override
    public boolean flywheel$deferredEmissive() {
        return flywheel$deferredEmissive;
    }

    @Override
    public boolean flywheel$deferredTranslucent() {
        return flywheel$deferredTranslucent;
    }

    @Override
    public boolean flywheel$emissiveLight() {
        return flywheel$emissiveLight;
    }

    @Override
    public float flywheel$renderScale() {
        return flywheel$renderScale;
    }

    @Override
    public boolean flywheel$nativeDepth() {
        return flywheel$nativeDepth;
    }
}
