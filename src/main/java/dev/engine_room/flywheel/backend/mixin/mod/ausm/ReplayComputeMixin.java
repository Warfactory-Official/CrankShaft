package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import dev.engine_room.flywheel.backend.engine.shaderpack.AusmReplayBridge;
import org.lwjgl.opengl.GL43;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.PipelineWorldFramebufferFinalization", remap = false)
public abstract class ReplayComputeMixin {
    // Keep native uniforms, images and barriers; gate only the dispatch for inactive material layers.
    @Redirect(method = "runComputePrograms(Ljava/util/List;Lcom/luna/ausm/api/pipeline/shader/RenderPass;II)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL43;glDispatchCompute(III)V"), require = 1)
    private void flw$replayCompute(int x, int y, int z) {
        if (!AusmReplayBridge.compute(x, y, z)) GL43.glDispatchCompute(x, y, z);
    }

    // The pack-owned indirect command stays intact; only far-layer replay uses the gated copy.
    @Redirect(method = "runComputePrograms(Ljava/util/List;Lcom/luna/ausm/api/pipeline/shader/RenderPass;II)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL43;glDispatchComputeIndirect(J)V"), require = 1)
    private void flw$replayIndirectCompute(long offset) {
        if (!AusmReplayBridge.computeIndirect(offset)) GL43.glDispatchComputeIndirect(offset);
    }
}
