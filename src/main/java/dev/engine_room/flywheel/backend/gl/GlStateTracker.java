package dev.engine_room.flywheel.backend.gl;

import dev.engine_room.flywheel.backend.gl.buffer.GlBufferType;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.*;

/**
 * Tracks bound buffers/vbos because GlStateManager doesn't do that for us.
 */
public class GlStateTracker {
    /** {@code GlBufferType.values()} clones on every call; cache once and reuse. Never mutate. */
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
        BUFFERS[GlBufferType.idFromTarget(target)] = id;
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

    public static State getRestoreState() {
        // GlStateManager.activeTextureUnit is the cached unit offset (0..n) maintained by every
        // setActiveTexture/OpenGlHelper.setActiveTexture call. We store the enum form here so
        // restore() can pass it straight back to GlStateManager.setActiveTexture.
        return new State(BUFFERS.clone(), vao, program, GL13.GL_TEXTURE0 + GlStateManager.activeTextureUnit);
    }

    public static void bindVao(int vao) {
        if (vao != GlStateTracker.vao) {
            GL30.glBindVertexArray(vao);
            GlStateTracker.vao = vao;
        }
    }

    public static void bindBuffer(GlBufferType type, int buffer) {
        if (BUFFERS[type.ordinal()] != buffer || type == GlBufferType.ELEMENT_ARRAY_BUFFER) {
            GL15.glBindBuffer(type.glEnum, buffer);
            BUFFERS[type.ordinal()] = buffer;
        }
    }

    // Call-site shims for the two native ARB GL functions we care about. ArbCallSiteTransformer
    // rewrites every external invokestatic targeting the originals to call through here, so the
    // GL state change still goes via the ARB function pointer (preserving caller intent across
    // drivers that resolve ARB vs core to different entries) while the tracker stays in sync.
    @SuppressWarnings("unused")
    public static void arbUseProgram(int program) {
        ARBShaderObjects.glUseProgramObjectARB(program);
        GlStateTracker.program = program;
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

            if (program != GlStateTracker.program) {
                GL20.glUseProgram(program);
                GlStateTracker.program = program;
            }

            GlStateManager.setActiveTexture(activeTexture);
        }

        @Override
        public void close() {
            restore();
        }
    }
}
