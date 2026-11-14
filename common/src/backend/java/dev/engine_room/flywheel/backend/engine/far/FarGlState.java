package dev.engine_room.flywheel.backend.engine.far;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.ARBDrawBuffersBlend;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;

import java.util.stream.IntStream;

/**
 * GL state borrowed by retained-surface preparation and drawing inside a LoD host's pass. Capture immediately
 * before that work on the render thread; close before the host resumes. The borrowed vertex array is rebound
 * after our draws without changing its element buffer.
 */
public final class FarGlState implements AutoCloseable {
    private static final TextureBindings[] NO_EXTRA_TEXTURES = {};
    private static final int[] TEXTURE_UNITS = IntStream.concat(IntStream.of(0, 1, 2),
            IntStream.range(10, Math.min(24,
                    GL11C.glGetInteger(GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)))).toArray();
    private static final int MAX_UNIFORM_BINDINGS = GL11C.glGetInteger(GL31C.GL_MAX_UNIFORM_BUFFER_BINDINGS);
    private static final int[] UNIFORM_BINDINGS = IntStream.concat(
                                                                   IntStream.range(0, 13), IntStream.of(26))
                                                           .filter(index -> index < MAX_UNIFORM_BINDINGS).toArray();
    private static final int[] STORAGE_BINDINGS = IntStream.concat(
                                                                   IntStream.range(0, 16), IntStream.of(24, 25, 27))
                                                           .filter(index -> index < GlCompat.MAX_SHADER_STORAGE_BUFFER_BINDINGS)
                                                           .toArray();
    private static final int TARGETS = 8;
    private static final int IMAGE_FIELDS = 6;
    private static final int BLEND_FIELDS = 6;
    private static final boolean INDIRECT_PARAMETERS = GlCompat.CAPABILITIES.OpenGL46
            || GlCompat.CAPABILITIES.GL_ARB_indirect_parameters;

    private final int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
    private final int vertexArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
    private final int drawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int readFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
    private final int activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
    private final int arrayBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
    private final int indirect = GL11C.glGetInteger(GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING);
    private final int dispatchIndirect = GL11C.glGetInteger(GL43C.GL_DISPATCH_INDIRECT_BUFFER_BINDING);
    private final int parameterBuffer = INDIRECT_PARAMETERS
            ? GL11C.glGetInteger(ARBIndirectParameters.GL_PARAMETER_BUFFER_BINDING_ARB) : 0;
    private final int uniform = GL11C.glGetInteger(GL31C.GL_UNIFORM_BUFFER_BINDING);
    private final int storage = GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
    private final int[] viewport = new int[4];
    private final int[] scissorBox = new int[4];
    private final int[] polygonMode = new int[2];
    private final int[] uniformBuffers = new int[UNIFORM_BINDINGS.length];
    private final long[] uniformStarts = new long[UNIFORM_BINDINGS.length];
    private final long[] uniformSizes = new long[UNIFORM_BINDINGS.length];
    private final int[] storageBuffers = new int[STORAGE_BINDINGS.length];
    private final long[] storageStarts = new long[STORAGE_BINDINGS.length];
    private final long[] storageSizes = new long[STORAGE_BINDINGS.length];
    private final int[] textureUnits;
    private final int[] arrayTextureUnits;
    private final int[] samplerUnits;
    private final TextureBindings[] extraTextureBindings;
    private final int[][] extraTextures;
    private final int[] textures;
    private final int[] arrayTextures;
    private final int[] samplers;
    private final int[] images = new int[8 * IMAGE_FIELDS];
    private final boolean depth = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
    private final boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
    private final int depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
    private final boolean stencil = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
    private final StencilFace stencilFront = StencilFace.read(false);
    private final StencilFace stencilBack = StencilFace.read(true);
    private final boolean depthClamp = GL11C.glIsEnabled(GL32C.GL_DEPTH_CLAMP);
    private final boolean[] clipDistance = new boolean[4];
    private final int frontFace = GL11C.glGetInteger(GL11C.GL_FRONT_FACE);
    private final boolean cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
    private final boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
    private final boolean polygonOffset = GL11C.glIsEnabled(GL11C.GL_POLYGON_OFFSET_FILL);
    private final float polygonFactor = GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_FACTOR);
    private final float polygonUnits = GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_UNITS);
    private final boolean[] blendEnabled = new boolean[TARGETS];
    private final int[] blendState = new int[TARGETS * BLEND_FIELDS];
    private final int[] colorMasks = new int[TARGETS];

    private FarGlState(int[] textureUnits, int[] arrayTextureUnits, TextureBindings[] extraTextureBindings) {
        this.textureUnits = textureUnits;
        this.arrayTextureUnits = arrayTextureUnits;
        this.extraTextureBindings = extraTextureBindings;
        extraTextures = new int[extraTextureBindings.length][];
        if (textureUnits == arrayTextureUnits && extraTextureBindings.length == 0) {
            samplerUnits = textureUnits;
        } else {
            var units = IntStream.concat(IntStream.of(textureUnits), IntStream.of(arrayTextureUnits));
            for (var bindings : extraTextureBindings) units = IntStream.concat(units, IntStream.of(bindings.units()));
            samplerUnits = units.distinct().toArray();
        }
        textures = new int[textureUnits.length];
        arrayTextures = new int[arrayTextureUnits.length];
        samplers = new int[samplerUnits.length];
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        // Iris skips a _viewport equal to its last one (MixinGlStateManager_FramebufferBinding), which a host's raw
        // glViewport leaves stale; passing the live viewport through it reconciles the cache before our own calls.
        GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, scissorBox);
        GL11C.glGetIntegerv(GL11C.GL_POLYGON_MODE, polygonMode);
        captureBuffers(GL31C.GL_UNIFORM_BUFFER_BINDING, GL31C.GL_UNIFORM_BUFFER_START,
                GL31C.GL_UNIFORM_BUFFER_SIZE, UNIFORM_BINDINGS, uniformBuffers, uniformStarts, uniformSizes);
        captureBuffers(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, GL43C.GL_SHADER_STORAGE_BUFFER_START,
                GL43C.GL_SHADER_STORAGE_BUFFER_SIZE, STORAGE_BINDINGS, storageBuffers, storageStarts, storageSizes);

        // A host may use raw GL; adopt its actual state before invoking cached Blaze3D setters.
        GlStateManager.activeTexture = activeTexture - GL13C.GL_TEXTURE0;
        for (int i = 0; i < textureUnits.length; i++) {
            int unit = textureUnits[i];
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
            textures[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            if (unit < 12) GlStateManager.TEXTURES[unit].binding = textures[i];
            if (textureUnits == arrayTextureUnits) {
                arrayTextures[i] = GL11C.glGetInteger(GL30C.GL_TEXTURE_BINDING_2D_ARRAY);
            }
        }
        if (textureUnits != arrayTextureUnits) {
            for (int i = 0; i < arrayTextureUnits.length; i++) {
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + arrayTextureUnits[i]);
                arrayTextures[i] = GL11C.glGetInteger(GL30C.GL_TEXTURE_BINDING_2D_ARRAY);
            }
        }
        for (int i = 0; i < extraTextureBindings.length; i++) {
            var bindings = extraTextureBindings[i];
            var saved = new int[bindings.units().length];
            extraTextures[i] = saved;
            for (int j = 0; j < saved.length; j++) {
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + bindings.units()[j]);
                saved[j] = GL11C.glGetInteger(bindings.bindingQuery());
            }
        }
        for (int i = 0; i < samplerUnits.length; i++) {
            samplers[i] = GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, samplerUnits[i]);
        }
        GlStateManager._activeTexture(activeTexture);
        for (int i = 0; i < clipDistance.length; i++) clipDistance[i] = GL11C.glIsEnabled(GL30C.GL_CLIP_DISTANCE0 + i);

        for (int image = 0; image < 8; image++) {
            int at = image * IMAGE_FIELDS;
            images[at] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_NAME, image);
            images[at + 1] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LEVEL, image);
            images[at + 2] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYERED, image);
            images[at + 3] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYER, image);
            images[at + 4] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_ACCESS, image);
            images[at + 5] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_FORMAT, image);
        }
        try (var stack = MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            for (int target = 0; target < TARGETS; target++) {
                int at = target * BLEND_FIELDS;
                blendEnabled[target] = GL30C.glIsEnabledi(GL11C.GL_BLEND, target);
                blendState[at] = GL30C.glGetIntegeri(GL14C.GL_BLEND_SRC_RGB, target);
                blendState[at + 1] = GL30C.glGetIntegeri(GL14C.GL_BLEND_DST_RGB, target);
                blendState[at + 2] = GL30C.glGetIntegeri(GL14C.GL_BLEND_SRC_ALPHA, target);
                blendState[at + 3] = GL30C.glGetIntegeri(GL14C.GL_BLEND_DST_ALPHA, target);
                blendState[at + 4] = GL30C.glGetIntegeri(GL20C.GL_BLEND_EQUATION_RGB, target);
                blendState[at + 5] = GL30C.glGetIntegeri(GL20C.GL_BLEND_EQUATION_ALPHA, target);
                GL30C.glGetBooleani_v(GL11C.GL_COLOR_WRITEMASK, target, mask);
                colorMasks[target] = (mask.get(0) != 0 ? 1 : 0) | (mask.get(1) != 0 ? 2 : 0)
                        | (mask.get(2) != 0 ? 4 : 0) | (mask.get(3) != 0 ? 8 : 0);
                GlStateManager.COLOR_MASK[target] = colorMasks[target];
            }
        }
        GlStateManager.readFbo = readFramebuffer;
        GlStateManager.writeFbo = drawFramebuffer;
        GlStateManager.DEPTH.mode.enabled = depth;
        GlStateManager.DEPTH.func = depthFunc;
        GlStateManager.DEPTH.mask = depthMask;
        GlStateManager.CULL.enable.enabled = cull;
        GlStateManager.SCISSOR.mode.enabled = scissor;
        GlStateManager.POLY_OFFSET.fill.enabled = polygonOffset;
        GlStateManager.POLY_OFFSET.factor = polygonFactor;
        GlStateManager.POLY_OFFSET.units = polygonUnits;
        #if MC >= 26.3
        System.arraycopy(blendEnabled, 0, GlStateManager.BLEND_ENABLE, 0, TARGETS);
        #else
        GlStateManager.BLEND[0].mode.enabled = blendEnabled[0];
        #endif
        synchronizeBlendCache();
        GlStateManager._glUseProgram(program);
        GlStateManager._glBindVertexArray(vertexArray);
    }

    public static FarGlState capture() {
        return capture(TEXTURE_UNITS, TEXTURE_UNITS);
    }

    /**
     * Borrows only the listed 2D and array texture bindings, with samplers on their union, for one complete host
     * graph. Lists replace the default texture set and remain unchanged until close. All other borrowed state uses
     * the usual contract. Each texture bind inside the graph must affect only its declared target.
     */
    public static FarGlState capture(int[] textureUnits, int[] arrayTextureUnits) {
        return capture(textureUnits, arrayTextureUnits, NO_EXTRA_TEXTURES);
    }

    /**
     * A distinct additional texture target and its binding query, on units selected by a host's linked samplers.
     * The target must differ from the explicit 2D/array targets. Keep the unit list unchanged until scope close.
     */
    public record TextureBindings(int target, int bindingQuery, int[] units) {
    }

    /**
     * Borrows additional declared texture targets, with samplers on the union of every supplied unit list.
     */
    public static FarGlState capture(int[] textureUnits, int[] arrayTextureUnits, TextureBindings... extraTextures) {
        return new FarGlState(textureUnits, arrayTextureUnits, extraTextures);
    }

    @Override
    public void close() {
        GlStateManager.readFbo = -1;
        GlStateManager.writeFbo = -1;
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GlStateManager._scissorBox(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        GlStateManager._glUseProgram(program);
        GlStateManager._glBindVertexArray(vertexArray);
        GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, arrayBuffer);
        GlStateManager._glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, indirect);
        GlStateManager._glBindBuffer(GL43C.GL_DISPATCH_INDIRECT_BUFFER, dispatchIndirect);
        if (INDIRECT_PARAMETERS) {
            GlStateManager._glBindBuffer(ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB, parameterBuffer);
        }
        restoreBuffers(GL31C.GL_UNIFORM_BUFFER, UNIFORM_BINDINGS, uniformBuffers, uniformStarts, uniformSizes);
        restoreBuffers(GL43C.GL_SHADER_STORAGE_BUFFER, STORAGE_BINDINGS, storageBuffers, storageStarts, storageSizes);
        GlStateManager._glBindBuffer(GL31C.GL_UNIFORM_BUFFER, uniform);
        GlStateManager._glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, storage);
        for (int i = 0; i < textureUnits.length; i++) {
            int unit = textureUnits[i];
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
            if (unit < 12) {
                // DSA bindings inside our passes bypass the 2D-binding cache.
                GlStateManager.TEXTURES[unit].binding = -1;
                GlStateManager._bindTexture(textures[i]);
            } else {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[i]);
            }
            if (textureUnits == arrayTextureUnits) {
                GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY, arrayTextures[i]);
            }
        }
        if (textureUnits != arrayTextureUnits) {
            for (int i = 0; i < arrayTextureUnits.length; i++) {
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + arrayTextureUnits[i]);
                GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY, arrayTextures[i]);
            }
        }
        for (int i = 0; i < extraTextureBindings.length; i++) {
            var bindings = extraTextureBindings[i];
            var saved = extraTextures[i];
            for (int j = 0; j < saved.length; j++) {
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + bindings.units()[j]);
                GL11C.glBindTexture(bindings.target(), saved[j]);
            }
        }
        for (int i = 0; i < samplerUnits.length; i++) {
            GL33C.glBindSampler(samplerUnits[i], samplers[i]);
        }
        GlStateManager._activeTexture(activeTexture);
        for (int image = 0; image < 8; image++) {
            int at = image * IMAGE_FIELDS;
            GL42C.glBindImageTexture(image, images[at], images[at + 1], images[at + 2] != 0, images[at + 3],
                    images[at + 4], images[at + 5]);
        }
        depth(depth);
        GlStateManager._depthFunc(depthFunc);
        GlStateManager._depthMask(depthMask);
        if (stencil) GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        else GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        stencilFront.restore(GL11C.GL_FRONT);
        stencilBack.restore(GL11C.GL_BACK);
        if (depthClamp) GL11C.glEnable(GL32C.GL_DEPTH_CLAMP);
        else GL11C.glDisable(GL32C.GL_DEPTH_CLAMP);
        for (int i = 0; i < clipDistance.length; i++) {
            if (clipDistance[i]) GL11C.glEnable(GL30C.GL_CLIP_DISTANCE0 + i);
            else GL11C.glDisable(GL30C.GL_CLIP_DISTANCE0 + i);
        }
        GL11C.glFrontFace(frontFace);
        cull(cull);
        scissor(scissor);
        polygonOffset(polygonOffset);
        GlStateManager._polygonOffset(polygonFactor, polygonUnits);
        GlStateManager._polygonMode(GL11C.GL_FRONT_AND_BACK, polygonMode[0]);
        restoreBlend();
        for (int target = 0; target < TARGETS; target++) {
            GlStateManager._colorMask(target, colorMasks[target]);
        }
        GlStateTracker.invalidateEncoderProgramCache();
    }

    private static void captureBuffers(int binding, int start, int size, int[] bindings, int[] buffers, long[] starts,
                                       long[] sizes) {
        for (int index = 0; index < buffers.length; index++) {
            buffers[index] = GL30C.glGetIntegeri(binding, bindings[index]);
            starts[index] = GL32C.glGetInteger64i(start, bindings[index]);
            sizes[index] = GL32C.glGetInteger64i(size, bindings[index]);
        }
    }

    private static void restoreBuffers(int target, int[] bindings, int[] buffers, long[] starts, long[] sizes) {
        for (int index = 0; index < buffers.length; index++) {
            if (sizes[index] == 0) GL30C.glBindBufferBase(target, bindings[index], buffers[index]);
            else GL30C.glBindBufferRange(target, bindings[index], buffers[index], starts[index], sizes[index]);
        }
    }

    private record StencilFace(int function, int reference, int valueMask, int writeMask, int fail,
                               int depthFail, int pass) {
        static StencilFace read(boolean back) {
            return new StencilFace(GL11C.glGetInteger(back ? GL20C.GL_STENCIL_BACK_FUNC : GL11C.GL_STENCIL_FUNC),
                    GL11C.glGetInteger(back ? GL20C.GL_STENCIL_BACK_REF : GL11C.GL_STENCIL_REF),
                    GL11C.glGetInteger(back ? GL20C.GL_STENCIL_BACK_VALUE_MASK : GL11C.GL_STENCIL_VALUE_MASK),
                    GL11C.glGetInteger(back ? GL20C.GL_STENCIL_BACK_WRITEMASK : GL11C.GL_STENCIL_WRITEMASK),
                    GL11C.glGetInteger(back ? GL20C.GL_STENCIL_BACK_FAIL : GL11C.GL_STENCIL_FAIL),
                    GL11C.glGetInteger(back ? GL20C.GL_STENCIL_BACK_PASS_DEPTH_FAIL : GL11C.GL_STENCIL_PASS_DEPTH_FAIL),
                    GL11C.glGetInteger(
                            back ? GL20C.GL_STENCIL_BACK_PASS_DEPTH_PASS : GL11C.GL_STENCIL_PASS_DEPTH_PASS));
        }

        void restore(int face) {
            GL20C.glStencilFuncSeparate(face, function, reference, valueMask);
            GL20C.glStencilMaskSeparate(face, writeMask);
            GL20C.glStencilOpSeparate(face, fail, depthFail, pass);
        }
    }

    private void synchronizeBlendCache() {
        GlStateManager.BlendState blend = #if MC >= 26.3 GlStateManager.BLEND #else GlStateManager.BLEND[0] #endif;
        blend.srcRgb = blendState[0];
        blend.dstRgb = blendState[1];
        blend.srcAlpha = blendState[2];
        blend.dstAlpha = blendState[3];
        blend.modeRgb = blendState[4];
        blend.modeAlpha = blendState[5];
    }

    private void restoreBlend() {
        // Blaze3D's tracked factors/equations apply globally, so restore the remaining indexed targets afterwards.
        GlStateManager._blendFuncSeparate(blendState[0], blendState[1], blendState[2], blendState[3]);
        GlStateManager._blendEquationSeparate(blendState[4], blendState[5]);
        #if MC >= 26.3
        for (int target = 0; target < TARGETS; target++) {
            if (blendEnabled[target]) GlStateManager._enableBlend(target);
            else GlStateManager._disableBlend(target);
        }
        #else
        if (blendEnabled[0]) GlStateManager._enableBlend(0);
        else GlStateManager._disableBlend(0);
        #endif
        for (int target = 1; target < TARGETS; target++) {
            int at = target * BLEND_FIELDS;
            ARBDrawBuffersBlend.glBlendFuncSeparateiARB(target, blendState[at], blendState[at + 1], blendState[at + 2],
                    blendState[at + 3]);
            ARBDrawBuffersBlend.glBlendEquationSeparateiARB(target, blendState[at + 4], blendState[at + 5]);
            #if MC < 26.3
            if (blendEnabled[target]) GL30C.glEnablei(GL11C.GL_BLEND, target);
            else GL30C.glDisablei(GL11C.GL_BLEND, target);
            #endif
        }
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

    private static void polygonOffset(boolean enabled) {
        if (enabled) GlStateManager._enablePolygonOffset();
        else GlStateManager._disablePolygonOffset();
    }
}
