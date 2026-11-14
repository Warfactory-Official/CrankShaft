package dev.engine_room.flywheel.backend.engine.far;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

/**
 * Bindings borrowed by the far renderer's compute-only host callbacks. Capture on the render thread immediately
 * before the named operation and close before its host resumes; raster state remains owned by the host.
 */
public final class FarComputeState implements AutoCloseable {
    private static final int[] NO_STORAGE_BINDINGS = {};
    private static final int[] PUBLICATION_STORAGE_BINDINGS = {0, 3, 7, 9, 11, 13, 14};
    private static final int IMAGE_FIELDS = 6;

    private final int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
    private final int activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
    private final int storage;
    private final int[] storageBindings;
    private final int[] storageBuffers;
    private final long[] storageStarts;
    private final long[] storageSizes;
    private final int firstTexture;
    private final int textureTarget;
    private final int[] textures;
    private final int[] samplers;
    private final int[] images;

    private FarComputeState(int[] storageBindings, int firstTexture, int textureCount, int textureTarget,
                            int imageCount) {
        this.storageBindings = storageBindings;
        storageBuffers = new int[storageBindings.length];
        storageStarts = new long[storageBindings.length];
        storageSizes = new long[storageBindings.length];
        storage = storageBindings.length == 0 ? 0 : GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
        for (int i = 0; i < storageBindings.length; i++) {
            int binding = storageBindings[i];
            storageBuffers[i] = GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, binding);
            storageStarts[i] = GL32C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_START, binding);
            storageSizes[i] = GL32C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_SIZE, binding);
        }
        this.firstTexture = firstTexture;
        this.textureTarget = textureTarget;
        textures = new int[textureCount];
        samplers = new int[textureCount];
        GlStateManager.activeTexture = activeTexture - GL13C.GL_TEXTURE0;
        int textureBinding = textureTarget == GL11C.GL_TEXTURE_2D ? GL11C.GL_TEXTURE_BINDING_2D
                : GL30C.GL_TEXTURE_BINDING_2D_ARRAY;
        for (int i = 0; i < textureCount; i++) {
            int unit = firstTexture + i;
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
            textures[i] = GL11C.glGetInteger(textureBinding);
            if (textureTarget == GL11C.GL_TEXTURE_2D && unit < 12) {
                GlStateManager.TEXTURES[unit].binding = textures[i];
            }
            samplers[i] = GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, unit);
        }
        GlStateManager._activeTexture(activeTexture);
        images = new int[imageCount * IMAGE_FIELDS];
        for (int image = 0; image < imageCount; image++) {
            int at = image * IMAGE_FIELDS;
            images[at] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_NAME, image);
            images[at + 1] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LEVEL, image);
            images[at + 2] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYERED, image);
            images[at + 3] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYER, image);
            images[at + 4] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_ACCESS, image);
            images[at + 5] = GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_FORMAT, image);
        }
        // Raw host programs bypass the managed bind hooks; the first compute bind must see the actual program.
        GlStateTracker._setProgram(program);
    }

    /**
     * Borrows only the 2D inputs and images used by {@link SceneOcclusion#capture(FarDepth)}.
     */
    public static FarComputeState captureOcclusion() {
        return new FarComputeState(NO_STORAGE_BINDINGS, 11, 2, GL11C.GL_TEXTURE_2D, 7);
    }

    /**
     * Borrows the atlas comparison inputs, storage ranges and output images used to publish layered bakes.
     */
    public static FarComputeState captureLayerPublication() {
        return new FarComputeState(PUBLICATION_STORAGE_BINDINGS, 10, 6, GL30C.GL_TEXTURE_2D_ARRAY, 6);
    }

    @Override
    public void close() {
        for (int i = 0; i < storageBindings.length; i++) {
            if (storageSizes[i] == 0) {
                GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, storageBindings[i], storageBuffers[i]);
            } else {
                GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, storageBindings[i], storageBuffers[i],
                        storageStarts[i], storageSizes[i]);
            }
        }
        if (storageBindings.length != 0) GlStateManager._glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, storage);
        for (int i = 0; i < textures.length; i++) {
            int unit = firstTexture + i;
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
            if (textureTarget == GL11C.GL_TEXTURE_2D && unit < 12) {
                GlStateManager.TEXTURES[unit].binding = -1;
                GlStateManager._bindTexture(textures[i]);
            } else {
                GL11C.glBindTexture(textureTarget, textures[i]);
            }
            GL33C.glBindSampler(unit, samplers[i]);
        }
        GlStateManager._activeTexture(activeTexture);
        for (int image = 0; image < images.length / IMAGE_FIELDS; image++) {
            int at = image * IMAGE_FIELDS;
            GL42C.glBindImageTexture(image, images[at], images[at + 1], images[at + 2] != 0, images[at + 3],
                    images[at + 4], images[at + 5]);
        }
        GlStateManager._glUseProgram(program);
        GlStateTracker.invalidateEncoderProgramCache();
    }
}
