package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.iris.compile.GuestPipelines;
import dev.engine_room.flywheel.iris.compile.GuestProgram;
import dev.engine_room.flywheel.iris.compile.FarLayerIrisBridge;
import dev.engine_room.flywheel.iris.engine.LateLightCapture;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IrisRenderingPipeline.class)
abstract class IrisRenderingPipelineMixin {
    @Shadow
    @Final
    private CompositeRenderer compositeRenderer;

    @ModifyExpressionValue(method = "addGbufferOrShadowSamplers", at = @At(value = "FIELD",
            target = "Lnet/irisshaders/iris/shaderpack/texture/TextureStage;GBUFFERS_AND_SHADOW:Lnet/irisshaders/iris/shaderpack/texture/TextureStage;"), require = 1)
    private TextureStage flywheel$guestSamplerStage(TextureStage stage) {
        return GuestProgram.compositeSamplers() ? TextureStage.COMPOSITE_AND_FINAL : stage;
    }

    @ModifyArg(method = "addGbufferOrShadowSamplers", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/samplers/IrisSamplers;addRenderTargetSamplers(Lnet/irisshaders/iris/gl/sampler/SamplerHolder;Ljava/util/function/Supplier;Lnet/irisshaders/iris/targets/RenderTargets;ZLnet/irisshaders/iris/pipeline/WorldRenderingPipeline;)V"), index = 3, require = 1)
    private boolean flywheel$guestFullscreenSamplers(boolean fullscreen) {
        return fullscreen || GuestProgram.compositeSamplers();
    }

    @WrapWithCondition(method = "addGbufferOrShadowSamplers", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/samplers/IrisSamplers;addLevelSamplers(Lnet/irisshaders/iris/gl/sampler/SamplerHolder;Lnet/irisshaders/iris/pipeline/WorldRenderingPipeline;Lnet/minecraft/client/renderer/texture/AbstractTexture;ZZZ)V"), require = 1)
    private boolean flywheel$guestLevelSamplers(SamplerHolder _, WorldRenderingPipeline _,
                                                AbstractTexture _, boolean _, boolean _,
                                                boolean _) {
        return !GuestProgram.compositeSamplers();
    }

    // Native resizing and setup have finished; begin passes clear capture storage immediately afterward.
    @Inject(method = "beginLevelRendering", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"))
    private void flywheel$beginDeferredCapture(CallbackInfo _) {
        compositeRenderer.flywheel$beginDeferredCapture();
    }

    // Iris suppresses render-pass framebuffer and viewport changes while shadows are active. Bake retained
    // surfaces before that state begins, after the frame plan has published newly unloaded visuals.
    @Inject(method = "renderShadows", at = @At("HEAD"), require = 1)
    private void flywheel$prepareFarFrame(CallbackInfo _) {
        var manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        if (manager != null) manager.prepareFarFrame();
    }

    @Inject(method = "finalizeLevelRendering", at = @At("HEAD"), require = 1)
    private void flywheel$captureLateLight(CallbackInfo _) {
        LateLightCapture.finish((IrisRenderingPipeline) (Object) this);
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/shaderpack/ShaderPack;getBufferObjects()Lit/unimi/dsi/fastutil/ints/Int2ObjectArrayMap;"))
    private Int2ObjectArrayMap<BuiltShaderStorageInfo> flywheel$acceptedBuffers(ShaderPack pack,
                                                                                Operation<Int2ObjectArrayMap<BuiltShaderStorageInfo>> original,
                                                                                @Local(argsOnly = true) ProgramSet programs) {
        var profile = pack.flywheel$deferredOit();
        if (profile != null && programs.flywheel$deferredOit() == null) {
            // A rejected adapter must leave the pack's own storage bindings intact.
            var nativeBuffers = new Int2ObjectArrayMap<>(original.call(pack));
            for (int binding : profile.storageBindings()) nativeBuffers.remove(binding);
            return nativeBuffers;
        }
        return original.call(pack);
    }

    @Inject(method = "destroy", at = @At("HEAD"), require = 1)
    private void flywheel$releaseGuestPrograms(CallbackInfo _) {
        GuestPipelines.release((IrisRenderingPipeline) (Object) this);
        FarLayerIrisBridge.reset();
    }
}
