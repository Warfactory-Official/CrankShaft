package dev.engine_room.flywheel.iris.experimental;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.irisshaders.iris.gl.framebuffer.ViewportData;

import java.util.List;

/**
 * Render-thread replay of a borrowed composite through a GPU-gated indexed draw.
 */
public interface CompositeIndirectDraw {
    void flywheel$drawIndirect(List<GpuTextureView> targets, ViewportData viewport, GpuBufferSlice command, long tick,
                               long frame);
}
