package dev.engine_room.flywheel.backend.gl;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.backend.gl.buffer.GlBufferType;
import org.lwjgl.opengl.*;

public class GlStateTracker {
    /**
     * {@code GlBufferType.values()} clones on every call; cache once and reuse. Never mutate.
     */
    private static final GlBufferType[] BUFFER_TYPES = GlBufferType.values();
    private static final int[] BUFFERS = new int[BUFFER_TYPES.length];
    private static int vao;
    private static int program;

    public static int getBuffer(GlBufferType type) {
        return BUFFERS[type.ordinal()];
    }

    public static int getVertexArray() {
        return vao;
    }

    public static int getProgram() {
        return program;
    }

    public static void _setBuffer(int target, int id) {
        int slot = GlBufferType.trackedIdFromTarget(target);
        if (slot >= 0) {
            BUFFERS[slot] = id;
        }
    }

    public static void _setBuffer(GlBufferType type, int id) {
        BUFFERS[type.ordinal()] = id;
    }

    public static void _setVertexArray(int id) {
        vao = id;
    }

    public static void _setProgram(int id) {
        program = id;
    }

    public static void _onBufferDeleted(int buffer) {
        if (buffer == 0) {
            return;
        }
        for (int i = 0; i < BUFFERS.length; i++) {
            if (BUFFERS[i] == buffer) {
                BUFFERS[i] = 0;
            }
        }
    }

    public static State getRestoreState() {
        return new State(BUFFERS.clone(), vao, program, GL13.glGetInteger(GL13.GL_ACTIVE_TEXTURE));
    }

    /**
     * Bind through vanilla's own entry point so the {@code GlStateManagerMixin} hook reconciles {@link #program}.
     */
    public static void useProgram(int handle) {
        if (handle != program) {
            GlStateManager._glUseProgram(handle);
        }
    }

    /**
     * Drop vanilla's cross-pass program cache: {@link GlCommandEncoder} skips glUseProgram when it
     * believes the program unchanged, so a bind made outside its trySetup would leave stale uniforms.
     */
    public static void invalidateEncoderProgramCache() {
        if (RenderSystem.getDevice().backend instanceof GlDevice glDevice
                && glDevice.createCommandEncoder() instanceof GlCommandEncoder encoder) {
            encoder.lastProgram = null;
        }
    }

    public static void bindVao(int vao) {
        if (vao != GlStateTracker.vao) {
            GL30.glBindVertexArray(vao);
            GlStateTracker.vao = vao;
        }
    }

    public static void bindBuffer(GlBufferType type, int buffer) {
        // ELEMENT_ARRAY_BUFFER is VAO state and DRAW_INDIRECT_BUFFER is raw-bound by the mesh tier --
        // both change behind the cache.
        if (BUFFERS[type.ordinal()] != buffer
                || type == GlBufferType.ELEMENT_ARRAY_BUFFER
                || type == GlBufferType.DRAW_INDIRECT_BUFFER) {
            GL15.glBindBuffer(type.glEnum, buffer);
            BUFFERS[type.ordinal()] = buffer;
        }
    }

    // ArbCallSiteTransformer redirects ARB GL calls through here so the tracker stays in sync.
    @SuppressWarnings("unused")
    public static void arbUseProgram(int program) {
        ARBShaderObjects.glUseProgramObjectARB(program);
        GlStateTracker.program = program;
        invalidateEncoderProgramCache();
    }

    @SuppressWarnings("unused")
    public static void arbBindBuffer(int target, int buffer) {
        ARBVertexBufferObject.glBindBufferARB(target, buffer);
        BUFFERS[GlBufferType.idFromTarget(target)] = buffer;
    }

    public record State(int[] buffers, int vao, int program, int activeTexture) implements AutoCloseable {
        public void restore() {
            if (vao != GlStateTracker.vao) {
                GL30.glBindVertexArray(vao);
                GlStateTracker.vao = vao;
            }

            for (int i = 0; i < BUFFER_TYPES.length; i++) {
                if (buffers[i] != BUFFERS[i] && BUFFER_TYPES[i] != GlBufferType.ELEMENT_ARRAY_BUFFER) {
                    GL15.glBindBuffer(BUFFER_TYPES[i].glEnum, buffers[i]);
                    BUFFERS[i] = buffers[i];
                }
            }

            useProgram(program);

            GL13.glActiveTexture(activeTexture);
        }

        @Override
        public void close() {
            restore();
        }
    }
}
