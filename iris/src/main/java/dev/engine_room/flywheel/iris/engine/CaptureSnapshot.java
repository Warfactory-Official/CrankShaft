package dev.engine_room.flywheel.iris.engine;

import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.iris.compile.GuestPipelines;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.iris.mixin.ProgramFallbackResolverAccessor;
import dev.engine_room.flywheel.iris.mixin.ShaderStorageBufferHolderAccessor;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL45C;

/**
 * A deferred capture tests its fragment against the depth at its seam, not {@code depthtex1}: a forward depth writer
 * drawn after the pre-translucent copy (a mod's depth-writing translucent, the routed guests) hides what lies behind it
 * from the resolve as its depth does from forward drawing. Taken once per frame, after those writers and before the
 * guest captures, guest terrain replay, the light captures and Sodium's native translucent terrain. The frame's capture
 * clear zeroes it (far), so captures before the seam keep {@code depthtex1}.
 */
public final class CaptureSnapshot {
    private CaptureSnapshot() {
    }

    public static void take(IrisRenderingPipeline pipeline) {
        var access = (IrisRenderingPipelineAccessor) pipeline;
        var profile = ((ProgramFallbackResolverAccessor) access.flywheel$resolver()).flywheel$programs()
                                                                                .flywheel$deferredOit();
        if (profile == null || !BackendManagerImpl.isBackendOn()) return;
        var spec = profile.pool();
        var buffers = ((ShaderStorageBufferHolderAccessor) access.flywheel$shaderStorageBuffers()).flywheel$buffers();
        var targets = access.flywheel$renderTargets();
        GuestPipelines.captureLightDepth(pipeline);
        int pixels = targets.getCurrentWidth() * targets.getCurrentHeight();
        GlCompat.pushDebugGroup("flywheel:iris/capture_snapshot");
        // After the clear compute's stores to the same buffer.
        GL42C.glMemoryBarrier(GL42C.GL_PIXEL_BUFFER_BARRIER_BIT);
        int previous = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, buffers[spec.snapshotBinding()].getId());
        GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, 0);
        GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0);
        GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0);
        GL45C.glGetTextureImage(((GlTexture) targets.getDepthTexture()).glId(), 0, GL11C.GL_DEPTH_COMPONENT,
                GL11C.GL_FLOAT, pixels * Float.BYTES, 0L);
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, previous);
        GlCompat.popDebugGroup();
    }
}
