package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
#if MC >= 26.3
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
#endif
import com.seibel.distanthorizons.common.render.blaze.BlazeDhTerrainRenderer;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import dev.engine_room.flywheel.impl.compat.NativeWaterRenderer;
import dev.engine_room.flywheel.impl.compat.NativeWaterShaders;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(BlazeDhTerrainRenderer.class)
abstract class DhBlazeWaterRendererMixin implements NativeWaterRenderer {
    @Shadow
    private RenderPipeline transparentPipeline;
    @Shadow
    private int frameIndexMod8;
    @Unique
    private @Nullable NativeWaterShaders flywheel$water;
    @Unique
    private boolean flywheel$replaying;
    @Unique
    private int flywheel$replayFrame;

    @Override
    public boolean flywheel$waterReady() {
        return flywheel$water != null && flywheel$water.ready();
    }

    @Override
    public NativeWaterShaders flywheel$waterShaders() {
        return Objects.requireNonNull(flywheel$water);
    }

    @Override
    public void flywheel$prepareWater(RenderParams params) {
        if (!flywheel$replaying) {
            flywheel$replayFrame = frameIndexMod8;
            flywheel$replaying = true;
        }
        frameIndexMod8 = flywheel$replayFrame;
    }

    @Override
    public void flywheel$finishWater() {
        flywheel$replaying = false;
    }

    // Capture only after an ordinary host draw has requested its pipeline; preflight adds no synchronous native link.
    @Inject(method = "render", at = @At("RETURN"))
    private void flywheel$captureWater(RenderParams params, boolean opaque,
                                       SortedArraySet<LodBufferContainer> buffers, IProfilerWrapper profiler,
                                       CallbackInfo ci) {
        if (opaque || ShadersModHelper.isShaderPackInUse()) return;
        #if MC >= 26.3
        var compiled = (FrontendRenderPipeline) RenderSystem.getCompiledPipeline(transparentPipeline);
        if (!(compiled.backendRenderPipeline() instanceof GlRenderPipeline pipeline)) return;
        #else
        if (!(RenderSystem.getDevice().precompilePipeline(transparentPipeline) instanceof GlRenderPipeline pipeline))
            return;
        #endif
        int nativeProgram = pipeline.program().getProgramId();
        if (flywheel$water == null || flywheel$water.closed() || flywheel$water.nativeProgram() != nativeProgram) {
            if (flywheel$water != null) flywheel$water.close();
            flywheel$water = NativeWaterShaders.capture("dh/blaze-water", nativeProgram);
        }
    }
}
