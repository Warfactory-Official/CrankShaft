package dev.engine_room.flywheel.iris.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.iris.compile.ContractProperties;
import dev.engine_room.flywheel.iris.compile.ContractShaderPack;
import dev.engine_room.flywheel.iris.compile.patches.ContractPatches;
import dev.engine_room.flywheel.iris.compile.patches.DeferredOitProfile;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import org.jspecify.annotations.Nullable;
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

    // Root not retained past construction; defines = program-source preprocessing set.
    @Inject(method = "<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V",
            at = @At("RETURN"))
    private void flywheel$loadContractProperties(Path root, Map<String, String> changedConfigs,
                                                 ImmutableList<StringPair> environmentDefines, boolean isZip,
                                                 CallbackInfo ci,
                                                 @Local(name = "finalEnvironmentDefines1") Iterable<StringPair> defines) {
        flywheel$contractProperties = ContractProperties.parse(flywheel$patchPlan.properties(), shaderPackOptions,
                defines);
        flywheel$patchPlan = null;
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
}
