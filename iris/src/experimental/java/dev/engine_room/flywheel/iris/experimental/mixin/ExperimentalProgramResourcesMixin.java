package dev.engine_room.flywheel.iris.experimental.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.iris.engine.ExperimentalReplayState;
import dev.engine_room.flywheel.iris.experimental.ExternalStorageBindings;
import net.irisshaders.iris.gl.sampler.SamplerHolder.TextureBinding;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.pipeline.transform.ShaderResourceLayout;
import net.irisshaders.iris.samplers.PackSamplers;
import net.irisshaders.iris.samplers.ProgramResources;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;

@Mixin(ProgramResources.class)
abstract class ExperimentalProgramResourcesMixin implements ExternalStorageBindings {
    @Shadow
    @Final
    private List<ShaderResourceLayout.Descriptor> storageDeclarations;
    @Unique
    private List<ShaderResourceLayout.Descriptor> flywheel$packStorage;
    @Unique
    private boolean flywheel$replayDepth;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void flywheel$replayDepthLayout(PackSamplers pack, ShaderResourceLayout layout, CallbackInfo ci) {
        flywheel$replayDepth = layout.uniforms().containsKey("flw_oitFarLayer");
    }

    @WrapMethod(method = "addTextureSampler(Lnet/irisshaders/iris/gl/sampler/SamplerHolder$TextureBinding;ZZ[Ljava/lang/String;)Z")
    private boolean flywheel$workingOpaqueDepth(TextureBinding binding, boolean defaultSampler, boolean draw,
                                                String[] names, Operation<Boolean> original) {
        if (!flywheel$replayDepth || !binding.key().equals("depthtex1"))
            return original.call(binding, defaultSampler, draw, names);
        var replacement = new TextureBinding("flywheel/replay/opaqueDepth", () ->
                ExperimentalReplayState.replaying && ExperimentalReplayState.opaqueDepth != null
                        ? ExperimentalReplayState.opaqueDepth : binding.texture().get(), binding.sampler(),
                binding.nativeSampler());
        return original.call(replacement, defaultSampler, true, names);
    }

    @Override
    public void flywheel$externalStorage(Set<String> names) {
        flywheel$packStorage = storageDeclarations.stream().filter(d -> !names.contains(d.name())).toList();
    }

    // Retain declarations for Vulkan access tracking; skip only pack-buffer lookup.
    @ModifyExpressionValue(method = "getBufferBindings", at = @At(value = "FIELD",
            target = "Lnet/irisshaders/iris/samplers/ProgramResources;storageDeclarations:Ljava/util/List;"))
    private List<ShaderResourceLayout.Descriptor> flywheel$packStorage(List<ShaderResourceLayout.Descriptor> original) {
        return flywheel$packStorage == null ? original : flywheel$packStorage;
    }

    @Inject(method = "finish", at = @At("HEAD"))
    private void flywheel$layerImage(CallbackInfo ci) {
        ((ProgramResources) (Object) this).addTextureImage(ExperimentalReplayState::layerNodes,
                InternalTextureFormat.R32UI, "flw_layerNode");
    }
}
