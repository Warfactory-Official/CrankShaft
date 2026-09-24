package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.iris.compile.GuestPipelines;
import net.minecraft.client.Minecraft;

import java.util.Optional;
import java.util.OptionalDouble;

final class GuestOitComposite {
    private GuestOitComposite() {
    }

    // The pass targets are placeholders: the composite program binds the pack framebuffer.
    static void draw(String label, boolean shadow) {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return;
        }
        try (RenderPass pass = RenderSystem.getDevice()
                                           .createCommandEncoder()
                                           .createRenderPass(() -> label, colorView, Optional.empty(), depthView,
                                                   OptionalDouble.empty())) {
            pass.setPipeline(GuestPipelines.oitComposite(shadow));
            pass.draw(3, 1, 0, 0);
            pass.setPipeline(GuestPipelines.oitDepth(shadow));
            pass.draw(3, 1, 0, 0);
        }
    }
}
