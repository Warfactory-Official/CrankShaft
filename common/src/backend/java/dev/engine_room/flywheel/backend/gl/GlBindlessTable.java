package dev.engine_room.flywheel.backend.gl;

import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.engine_room.flywheel.backend.engine.BindlessSlots;
import dev.engine_room.flywheel.backend.engine.MaterialSamplers;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.texture.TextureManager;
import org.lwjgl.opengl.ARBBindlessTexture;
import org.lwjgl.opengl.GL45C;

/**
 * GL_ARB_bindless_texture handle table: one std430 SSBO of {@code uvec2} handles at binding
 * {@link #HANDLES_BINDING}, indexed by the draw's {@code packedTexIndices} slot.
 */
public final class GlBindlessTable {
    public static final int HANDLES_BINDING = 8;
    // Two slots can still resolve the same (texture, sampler) handle, and re-making a resident
    // handle resident is a GL error -- dedup by handle.
    private static final LongOpenHashSet resident = new LongOpenHashSet();
    private static int ssbo;
    private static long ssboBytes;
    // Staleness is keyed by OBJECT identity, never by GL name or handle value: a reload closes the
    // old GlTexture and the driver may recycle both the name and the handle value.
    private static GpuTexture[] writtenTexture = new GpuTexture[64];
    private static GpuSampler[] writtenSampler = new GpuSampler[64];
    private static long[] handles = new long[64];

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
            GpuTexture old = writtenTexture[slot];
            if (old != null && old != textureManager.getTexture(BindlessSlots.key(i).texture()).getTextureView()
                                                    .texture()) {
                resident.remove(handles[slot]);
                writtenTexture[slot] = null;
            }
        }
        for (int i = 0; i < count; i++) {
            BindlessSlots.Key key = BindlessSlots.key(i);
            GpuTexture texture = textureManager.getTexture(key.texture()).getTextureView().texture();
            int slot = i + BindlessSlots.FIRST_MATERIAL_SLOT;
            // The policy sampler is not immutable: the atlas filter follows Sodium's live pixel-filtering setting.
            GpuSampler sampler = MaterialSamplers.get(key.texture(), key.blur(), key.mipmap());
            if (writtenTexture[slot] == texture && writtenSampler[slot] == sampler) {
                continue;
            }
            long handle = ARBBindlessTexture.glGetTextureSamplerHandleARB(((GlTexture) texture).glId(),
                    (int) ((GlSampler) sampler).getId());
            if (resident.add(handle)) {
                ARBBindlessTexture.glMakeTextureHandleResidentARB(handle);
            }
            GL45C.glNamedBufferSubData(ssbo, (long) slot * Long.BYTES, new long[]{handle});
            writtenTexture[slot] = texture;
            writtenSampler[slot] = sampler;
            handles[slot] = handle;
        }
    }

    /**
     * Raw-bind the handle SSBO; untracked binding point, safe inside an open pass like the draw SSBOs.
     */
    public static void bind() {
        if (ssbo != 0) {
            GL45C.glBindBufferBase(GL45C.GL_SHADER_STORAGE_BUFFER, HANDLES_BINDING, ssbo);
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
            GL45C.glNamedBufferData(ssbo, bytes, GL45C.GL_STATIC_DRAW);
            ssboBytes = bytes;
            int cap = (int) (bytes / Long.BYTES);
            long[] grown = new long[cap];
            System.arraycopy(handles, 0, grown, 0, handles.length);
            handles = grown;
            // Deliberately NOT copying the written* arrays: nulls force a full rewrite into the reallocated buffer.
            writtenTexture = new GpuTexture[cap];
            writtenSampler = new GpuSampler[cap];
        }
    }
}
