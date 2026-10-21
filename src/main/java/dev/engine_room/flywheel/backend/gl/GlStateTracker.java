package dev.engine_room.flywheel.backend.gl;

import dev.engine_room.flywheel.backend.gl.buffer.GlBufferType;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.*;

import java.util.Arrays;

/**
 * Tracks bound buffers/vbos because GlStateManager doesn't do that for us.
 */
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

    // Native renderers can bind through LWJGL 3 entry points that bypass the LWJGL 2 tracking mixins.
    public static void invalidate() {
        var caps = GlCompat.CAPABILITIES;
        for (var type : BUFFER_TYPES) {
            var supported = switch (type) {
                case DRAW_INDIRECT_BUFFER -> caps.OpenGL40 || caps.GL_ARB_draw_indirect;
                case ATOMIC_COUNTER_BUFFER -> caps.OpenGL42 || caps.GL_ARB_shader_atomic_counters;
                case DISPATCH_INDIRECT_BUFFER -> caps.OpenGL43 || caps.GL_ARB_compute_shader;
                case SHADER_STORAGE_BUFFER -> caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object;
                default -> true;
            };
            if (supported) BUFFERS[type.ordinal()] = -1;
        }
        vao = -1;
        program = -1;
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

    // Compat with shader hosts: raw sampler binds must also update Minecraft's texture cache.
    public static void hostActiveTexture(int texture) {
        GL13.glActiveTexture(texture);
        var unit = texture - GL13.GL_TEXTURE0;
        if (unit >= GlStateManager.textureState.length) {
            var previous = GlStateManager.textureState;
            var expanded = Arrays.copyOf(previous, GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS));
            for (var index = previous.length; index < expanded.length; index++) {
                expanded[index] = new GlStateManager.TextureState();
                expanded[index].textureName = -1;
            }
            GlStateManager.textureState = expanded;
        }
        GlStateManager.activeTextureUnit = unit;
    }

    public static void hostBindTexture(int target, int texture) {
        GL11.glBindTexture(target, texture);
        var unit = GlStateManager.activeTextureUnit;
        if (target == GL11.GL_TEXTURE_2D && unit < GlStateManager.textureState.length) {
            GlStateManager.textureState[unit].textureName = texture;
        }
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

            // Raw instance-buffer binds can leave the actual unit ahead of Minecraft's cache after an exception.
            hostActiveTexture(activeTexture);
        }

        @Override
        public void close() {
            restore();
        }
    }
}
