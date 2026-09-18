package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.iris.mixin.ShaderStorageBufferHolderAccessor;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;

/**
 * Iris binds a pack's storage buffers once per pipeline; engine SSBO binds clobber them.
 */
public final class GuestSsbos {
    // Above IndirectBuffers/LightBuffers/MatrixBuffer (0..7).
    static final int BINDING_OFFSET = 8;
    // Terrain draws additionally hold the region input (10) and section fade (11) buffers, so a pack's buffers
    // have to clear those too.
    public static final int TERRAIN_BINDING_OFFSET = 12;

    private GuestSsbos() {
    }

    /**
     * The pack's buffers at their shifted guest bindings; relative buffers are reallocated on resize.
     */
    public static void bindForGuestTerrain(IrisRenderingPipeline pipeline) {
        bindAt(pipeline, TERRAIN_BINDING_OFFSET);
    }

    public static void bindForGuest(IrisRenderingPipeline pipeline) {
        bindAt(pipeline, BINDING_OFFSET);
    }

    private static void bindAt(IrisRenderingPipeline pipeline, int offset) {
        ShaderStorageBufferHolder holder = ((IrisRenderingPipelineAccessor) pipeline).flywheel$shaderStorageBuffers();
        if (holder == null) {
            return;
        }
        for (ShaderStorageBuffer buffer : ((ShaderStorageBufferHolderAccessor) holder).flywheel$buffers()) {
            if (buffer != null) {
                GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, buffer.getIndex() + offset,
                        buffer.getId());
            }
        }
    }

    /**
     * The pack's buffers back at their own bindings, after engine compute/draws.
     */
    public static void restore(IrisRenderingPipeline pipeline) {
        ShaderStorageBufferHolder holder = ((IrisRenderingPipelineAccessor) pipeline).flywheel$shaderStorageBuffers();
        if (holder != null) {
            holder.setupBuffers();
        }
    }
}
