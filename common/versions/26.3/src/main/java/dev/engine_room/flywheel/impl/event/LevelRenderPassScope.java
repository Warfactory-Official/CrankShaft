package dev.engine_room.flywheel.impl.event;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.compile.NativeOitWarmup;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.BlockDestructionProgress;

import java.util.SortedSet;

/**
 * Gives the engine a closed-pass interval after vanilla solid features, then resumes the caller's exact attachments.
 * The caller replaces its render-pass local with the returned pass, so classic transparency and both resource-close
 * paths consume the resumed pass. Render thread only.
 */
public final class LevelRenderPassScope {
    private LevelRenderPassScope() {
    }

    public static RenderPass afterSolid(RenderPass pass, VisualizationManagerImpl manager, RenderContextImpl ctx) {
        requireSupported(manager);
        RenderPassDescriptor continuation = suspend(pass);
        manager.renderDispatcher().afterEntities(ctx);
        manager.renderDispatcher().beforeCrumbling(ctx,
                (Long2ObjectOpenHashMap<SortedSet<BlockDestructionProgress>>) ctx.level().destructionProgress());
        return resume(continuation);
    }

    /** Rejects unfinished producer contracts before yielding the host pass or drawing engine terrain. */
    public static void requireSupported(VisualizationManagerImpl manager) {
        Backend backend = BackendManagerImpl.currentBackend();
        if (Minecraft.getInstance().gameRenderer.gameRenderState().optionsRenderState.improvedTransparency
                && !NativeOitWarmup.supports(backend)) {
            throw new UnsupportedOperationException(
                    "CrankShaft 26.3 native Improved Transparency does not support backend "
                    + Backend.REGISTRY.getIdOrThrow(backend));
        }
        if (manager.isShaderPackGuest()
                && Minecraft.getInstance().gameRenderer.gameRenderState().optionsRenderState.improvedTransparency) {
            throw new UnsupportedOperationException(
                    "Iris shaderpacks own translucency and require native Improved Transparency to remain disabled");
        }
    }

    /** Closes {@code pass}; {@link #resume} reopens its attachments. */
    public static RenderPassDescriptor suspend(RenderPass pass) {
        RenderPassDescriptor continuation = continuation(pass);
        pass.close();
        return continuation;
    }

    public static RenderPass resume(RenderPassDescriptor descriptor) {
        RenderPass resumed = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor);
        RenderSystem.bindDefaultUniforms(resumed);
        return resumed;
    }

    private static RenderPassDescriptor continuation(RenderPass pass) {
        RenderPassDescriptor descriptor = ((RenderPassDescriptorAccess) pass).flywheel$descriptor();
        RenderPassDescriptor.Builder builder = RenderPassDescriptor.builder(() -> "Main after engine")
                .withRenderArea(descriptor.renderArea());
        for (var attachment : descriptor.colorAttachments()) {
            if (attachment == null) {
                builder.withUnusedColorAttachment();
            } else {
                builder.withColorAttachment(attachment.textureView());
            }
        }
        if (descriptor.depthAttachment() != null) {
            builder.withDepthAttachment(descriptor.depthAttachment().textureView());
        }
        return builder.build();
    }
}
