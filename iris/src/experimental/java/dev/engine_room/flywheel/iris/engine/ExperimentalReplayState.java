package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.minecraft.client.Minecraft;

/**
 * Render-thread replay uniforms and readonly layer IDs shared with pack programs.
 */
public final class ExperimentalReplayState {
    public static int layer;
    public static boolean replaying;
    public static GpuTextureView opaqueDepth;
    private static GpuTexture nodes;
    private static GpuTextureView nodeView;

    private ExperimentalReplayState() {
    }

    public static GpuTextureView layerNodes() {
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (nodes == null || nodes.getWidth(0) != target.width || nodes.getHeight(0) != target.height) {
            releaseImage();
            nodes = RenderSystem.getDevice()
                                .createTexture("flywheel replay layer ids", ShaderStorageBuffer.USAGE_STORAGE
                                                | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                                        GpuFormat.R32_UINT, target.width, target.height, 1, 1);
            nodeView = RenderSystem.getDevice().createTextureView(nodes);
        }
        return nodeView;
    }

    public static void release() {
        releaseImage();
        layer = 0;
        replaying = false;
        opaqueDepth = null;
    }

    private static void releaseImage() {
        if (nodes != null) {
            var oldView = nodeView;
            var oldTexture = nodes;
            Iris.getRenderBackend().retire(() -> {
                oldView.close();
                oldTexture.close();
            });
            nodes = null;
            nodeView = null;
        }
    }
}
