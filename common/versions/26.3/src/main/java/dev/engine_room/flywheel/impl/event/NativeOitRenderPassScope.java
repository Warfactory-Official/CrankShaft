package dev.engine_room.flywheel.impl.event;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.NativeOitScope;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.VkNativeOitPipelines;
import dev.engine_room.flywheel.backend.engine.OitFrame;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Redirects native OIT pass creation to the engine's current producer attachments while a {@link NativeOitScope}
 * is open. Native parameters retain their terrain water mask, main, or cloud depth attachment.
 */
public final class NativeOitRenderPassScope {
    private NativeOitRenderPassScope() {
    }

    public static boolean active() {
        return NativeOitScope.active() != null;
    }

    public static RenderPass create(Supplier<String> label, OitRenderPassProvider.Parameters parameters) {
        NativeOitScope scope = NativeOitScope.active();
        if (scope.owner() != null) return scope.owner().borrow(label);
        RenderPassDescriptor descriptor = scope.descriptor();
        OitMode mode = scope.mode();
        OitFramebuffer framebuffer = scope.framebuffer();
        var builder = RenderPassDescriptor.builder(() -> "flywheel:native_oit/" + label.get())
                .withRenderArea(descriptor.renderArea());
        List<@Nullable GpuTextureView> colors = new ArrayList<>();
        for (var attachment : descriptor.colorAttachments()) {
            GpuTextureView view = attachment == null ? null : mode == OitMode.DEPTH_RANGE && colors.isEmpty()
                    ? framebuffer.depthBoundsView() : attachment.textureView();
            if (view == null) builder.withUnusedColorAttachment();
            else builder.withColorAttachment(view);
            colors.add(view);
        }
        GpuTextureView depth = parameters.depthTextureView;
        builder.withDepthAttachment(depth);
        RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(builder.build());
        RenderSystem.bindDefaultUniforms(pass);
        if (scope.vulkan()) {
            VkNativeOitPipelines.bindPass(colors, depth);
        } else if (framebuffer != null) {
            OitFrame frame = scope.frame();
            framebuffer.bindOitReads(pass, mode, frame.blueNoiseView(), frame.oitSampler(), frame.noiseSampler());
        }
        return pass;
    }
}
