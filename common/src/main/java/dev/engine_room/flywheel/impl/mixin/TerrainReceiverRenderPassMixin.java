package dev.engine_room.flywheel.impl.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
#if MC >= 26.3
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
#else
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Unique;
#endif
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverBuffers;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

#if MC >= 26.3
@Mixin(FrontendRenderPass.class)
#else
@Mixin(targets = {"com.mojang.blaze3d.opengl.GlRenderPass", "com.mojang.blaze3d.vulkan.VulkanRenderPass"})
#endif
abstract class TerrainReceiverRenderPassMixin {
    #if MC >= 26.3
    @Shadow private @Nullable FrontendRenderPipeline boundPipeline;
    #else
    @Unique
    private boolean flywheel$receiverPipeline;

    @Inject(method = "setPipeline", at = @At("RETURN"))
    private void flywheel$pipeline(RenderPipeline pipeline, CallbackInfo _) {
        flywheel$receiverPipeline = pipeline.getLocation().getPath().startsWith("pipeline/terrain_overlay/");
    }
    #endif

    @Inject(method = "setVertexBuffer", at = @At("RETURN"))
    private void flywheel$bindReceivers(int slot, @Nullable GpuBufferSlice vertices, CallbackInfo _) {
        if (slot != 0 || vertices == null) return;
        #if MC >= 26.3
        if (boundPipeline == null || !boundPipeline.uniformIndices().containsKey("TerrainReceiverIds")) return;
        #else
        if (!flywheel$receiverPipeline) return;
        #endif
        #if MC >= 26.3
        ((RenderPass)this).setUniform("TerrainReceiverIds", TerrainReceiverBuffers.get(vertices.buffer()));
        #else
        ((RenderPassBackend) this).setUniform("TerrainReceiverIds", TerrainReceiverBuffers.get(vertices.buffer()));
        #endif
    }
}
