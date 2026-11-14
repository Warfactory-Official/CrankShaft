package dev.engine_room.flywheel.iris.compile;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;

/**
 * State borrowed while a far surface is submitted inside a host's GL pass.
 */
public final class FarIrisState implements AutoCloseable {
    private static final int[] NO_UNITS = new int[0];
    private final int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
    private final int framebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int vertexArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
    private final int indirect = GL11C.glGetInteger(GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING);
    private final int activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
    private final int storage = GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
    private final int[] viewport = new int[4];
    private final boolean depth = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
    private final boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
    private final int depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
    private final boolean stencil = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
    private final boolean cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
    private final boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
    private final int[] ssbos;
    private final int[] storageBindings;
    private final long[] storageStarts;
    private final long[] storageSizes;
    private final int[] units;
    private final int[] textures;
    private final int[] bufferTextureUnits;
    private final int[] bufferTextures;
    private final int[] samplers;
    private final boolean[] blends;
    private final boolean[][] colors;

    private FarIrisState(int[] units, int[] bufferTextureUnits, int targets, int[] storageBindings) {
        this.units = units;
        this.bufferTextureUnits = bufferTextureUnits;
        this.storageBindings = storageBindings;
        int storageCount = storageBindings.length;
        ssbos = new int[storageCount];
        storageStarts = new long[storageCount];
        storageSizes = new long[storageCount];
        textures = new int[units.length];
        bufferTextures = new int[bufferTextureUnits.length];
        samplers = new int[units.length];
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        for (int i = 0; i < units.length; i++) {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + units[i]);
            textures[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            if (units[i] < 12) GlStateManager._bindTexture(textures[i]);
            samplers[i] = GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, units[i]);
        }
        for (int i = 0; i < bufferTextureUnits.length; i++) {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + bufferTextureUnits[i]);
            bufferTextures[i] = GL11C.glGetInteger(GL31C.GL_TEXTURE_BINDING_BUFFER);
        }
        GlStateManager._activeTexture(activeTexture);
        for (int i = 0; i < ssbos.length; i++) {
            ssbos[i] = GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, storageBindings[i]);
            if (ssbos[i] != 0) {
                storageStarts[i] = GL32C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_START, storageBindings[i]);
                storageSizes[i] = GL32C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_SIZE, storageBindings[i]);
            }
        }
        blends = new boolean[targets];
        colors = new boolean[targets][4];
        for (int i = 0; i < targets; i++) {
            blends[i] = GL30C.glIsEnabledi(GL11C.GL_BLEND, i);
            try (var stack = MemoryStack.stackPush()) {
                var mask = stack.malloc(4);
                GL30C.glGetBooleani_v(GL11C.GL_COLOR_WRITEMASK, i, mask);
                for (int j = 0; j < 4; j++) colors[i][j] = mask.get(j) != 0;
            }
        }
        GlStateManager._glUseProgram(program);
        GlStateManager._glBindVertexArray(vertexArray);
        depth(depth);
        GlStateManager._depthFunc(depthFunc);
        GlStateManager._depthMask(depthMask);
        cull(cull);
        scissor(scissor);
        for (int i = 0; i < blends.length; i++) {
            blend(i, blends[i]);
            GlStateManager._colorMask(i, colorMask(colors[i]));
        }
    }

    /**
     * Borrows the named 2D texture/sampler units and storage bindings until close, on the render thread.
     */
    public static FarIrisState capture(int[] units, int targets, int... storageBindings) {
        return new FarIrisState(units, NO_UNITS, targets, storageBindings);
    }

    /**
     * Borrows 2D texture/sampler units, additional buffer-texture units and storage bindings until close. Render thread.
     */
    public static FarIrisState capture(int[] units, int[] bufferTextureUnits, int targets, int... storageBindings) {
        return new FarIrisState(units, bufferTextureUnits, targets, storageBindings);
    }

    @Override
    public void close() {
        GlStateManager.writeFbo = -1;
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GlStateManager._glUseProgram(program);
        GlStateManager._glBindVertexArray(vertexArray);
        GlStateManager._glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, indirect);
        for (int i = 0; i < units.length; i++) {
            if (units[i] < 12) {
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + units[i]);
                GlStateManager._bindTexture(textures[i]);
            } else {
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + units[i]);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[i]);
            }
            GL33C.glBindSampler(units[i], samplers[i]);
        }
        for (int i = 0; i < bufferTextureUnits.length; i++) {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + bufferTextureUnits[i]);
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, bufferTextures[i]);
        }
        GlStateManager._activeTexture(activeTexture);
        for (int i = 0; i < ssbos.length; i++) {
            if (storageSizes[i] == 0)
                GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, storageBindings[i], ssbos[i]);
            else GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, storageBindings[i], ssbos[i], storageStarts[i],
                    storageSizes[i]);
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, storage);
        depth(depth);
        GlStateManager._depthFunc(depthFunc);
        GlStateManager._depthMask(depthMask);
        enabled(GL11C.GL_STENCIL_TEST, stencil);
        cull(cull);
        scissor(scissor);
        for (int i = 0; i < blends.length; i++) {
            blend(i, blends[i]);
            GlStateManager._colorMask(i, colorMask(colors[i]));
        }
    }

    private static int colorMask(boolean[] mask) {
        return (mask[0] ? 1 : 0) | (mask[1] ? 2 : 0) | (mask[2] ? 4 : 0) | (mask[3] ? 8 : 0);
    }

    private static void depth(boolean enabled) {
        if (enabled) GlStateManager._enableDepthTest();
        else GlStateManager._disableDepthTest();
    }

    private static void cull(boolean enabled) {
        if (enabled) GlStateManager._enableCull();
        else GlStateManager._disableCull();
    }

    private static void scissor(boolean enabled) {
        if (enabled) GlStateManager._enableScissorTest();
        else GlStateManager._disableScissorTest();
    }

    private static void blend(int target, boolean enabled) {
        if (enabled) GlStateManager._enableBlend(target);
        else GlStateManager._disableBlend(target);
    }

    private static void enabled(int capability, boolean enabled) {
        if (enabled) GL11C.glEnable(capability);
        else GL11C.glDisable(capability);
    }
}
