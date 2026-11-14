package dev.engine_room.flywheel.backend.gl;

import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.engine_room.flywheel.backend.engine.BindlessSlots;
import dev.engine_room.flywheel.backend.engine.MaterialSamplers;
import net.minecraft.client.renderer.texture.TextureManager;
import org.lwjgl.opengl.*;

/**
 * GL_ARB_bindless_texture handle table: one std430 SSBO of {@code uvec2} handles at binding
 * {@link #HANDLES_BINDING}, indexed by the draw's {@code packedTexIndices} slot.
 */
public final class GlBindlessTable {
    public static final int HANDLES_BINDING = 8;
    private static int ssbo;
    private static long ssboBytes;
    // Staleness is keyed by OBJECT identity, never by GL name or handle value: a reload closes the
    // old GlTexture and the driver may recycle both the name and the handle value.
    private static GpuTexture[] writtenTexture = new GpuTexture[64];
    private static GpuSampler[] writtenSampler = new GpuSampler[64];
    private static GpuTexture[] resolved = new GpuTexture[64];
    private static long[] handles = new long[64];
    private static int version;

    private GlBindlessTable() {
    }

    public static void refresh(TextureManager textureManager) {
        int count = BindlessSlots.count();
        if (count == 0) {
            return;
        }
        ensureCapacity(count);
        for (int i = 0; i < count; i++) {
            int slot = i + BindlessSlots.FIRST_MATERIAL_SLOT;
            GpuTexture texture = textureManager.getTexture(BindlessSlots.key(i).texture()).getTextureView().texture();
            resolved[slot] = texture;
        }
        for (int i = 0; i < count; i++) {
            BindlessSlots.Key key = BindlessSlots.key(i);
            int slot = i + BindlessSlots.FIRST_MATERIAL_SLOT;
            GpuTexture texture = resolved[slot];
            // The policy sampler is not immutable: the atlas filter follows Sodium's live pixel-filtering setting.
            GpuSampler sampler = MaterialSamplers.get(key.texture(), key.blur(), key.mipmap(), key.useTextureSampler());
            if (writtenTexture[slot] == texture && writtenSampler[slot] == sampler) {
                continue;
            }
            long handle = ARBBindlessTexture.glGetTextureSamplerHandleARB(((GlTexture) texture).glId(),
                    ((GlSampler) sampler).getId());
            makeResident(handle);
            GL45C.glNamedBufferSubData(ssbo, (long) slot * Long.BYTES, new long[]{handle});
            version++;
            writtenTexture[slot] = texture;
            writtenSampler[slot] = sampler;
            handles[slot] = handle;
        }
    }

    public static long handle(int slot) {
        return handles[slot];
    }

    /**
     * Makes a shared material handle resident once, including shaderpack companion textures. Render thread.
     */
    public static void makeResident(long handle) {
        // Texture deletion ends residency; a reload may recycle the same handle for a new texture object.
        if (!ARBBindlessTexture.glIsTextureHandleResidentARB(handle)) {
            ARBBindlessTexture.glMakeTextureHandleResidentARB(handle);
        }
    }

    /**
     * Bumps whenever a slot's handle changes.
     */
    public static int version() {
        return version;
    }

    /**
     * Raw-bind the handle SSBO; untracked binding point, safe inside an open pass like the draw SSBOs.
     */
    public static void bind() {
        if (ssbo != 0) {
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, HANDLES_BINDING, ssbo);
        }
    }

    private static void ensureCapacity(int materialCount) {
        int slots = materialCount + BindlessSlots.FIRST_MATERIAL_SLOT;
        long bytes = Long.highestOneBit(slots * (long) Long.BYTES - 1) << 1;
        bytes = Math.max(bytes, 1024);
        if (ssbo == 0) {
            ssbo = GL45C.glCreateBuffers();
        }
        if (bytes > ssboBytes) {
            GL45C.glNamedBufferData(ssbo, bytes, GL15C.GL_STATIC_DRAW);
            ssboBytes = bytes;
            int cap = (int) (bytes / Long.BYTES);
            long[] grown = new long[cap];
            System.arraycopy(handles, 0, grown, 0, handles.length);
            handles = grown;
            // Deliberately NOT copying the written* arrays: nulls force a full rewrite into the reallocated buffer.
            writtenTexture = new GpuTexture[cap];
            writtenSampler = new GpuSampler[cap];
            resolved = new GpuTexture[cap];
        }
    }
}
