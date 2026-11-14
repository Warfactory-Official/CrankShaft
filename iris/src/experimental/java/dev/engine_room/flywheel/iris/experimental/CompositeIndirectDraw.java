package dev.engine_room.flywheel.iris.experimental;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.iris.compile.ForwardOit;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Render-thread fullscreen composites through GPU-gated indexed draws.
 */
public interface CompositeIndirectDraw {
    void flywheel$drawIndirect(List<GpuTextureView> targets, ViewportData viewport, GpuBufferSlice command,
                               ForwardOit.@Nullable SnapshotRect scissor, long tick, long frame);
}
