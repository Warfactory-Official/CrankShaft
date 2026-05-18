package dev.engine_room.flywheel.backend.gl;

import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU-completion fence. Inserted into the command stream at construction; {@link #isSignaled()}
 * polls without flushing or stalling. Used by {@code StagingBuffer} to track "the GPU has finished
 * reading staging-ring region R" so the ring slot can be reclaimed for the next frame's writes.
 */
public final class GlFence {

    private final long fence;

    public GlFence() {
        fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    public boolean isSignaled() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long ptr = stack.ncalloc(Integer.BYTES, 0, Integer.BYTES);
            GL32.nglGetSynciv(fence, GL32.GL_SYNC_STATUS, 1, MemoryUtil.NULL, ptr);
            return MemoryUtil.memGetInt(ptr) == GL32.GL_SIGNALED;
        }
    }

    public void delete() {
        GL32.glDeleteSync(fence);
    }
}
