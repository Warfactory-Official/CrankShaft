package dev.engine_room.flywheel.iris.compile;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.engine_room.flywheel.backend.engine.BindlessSlots;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

import java.util.Arrays;

/**
 * Iris's albedo-derived uniforms per bindless slot, for merged-texture guest batches: {@code ivec4[2 * slot]} =
 * ({@code atlasSize}, {@code gtextureSize}), {@code [2 * slot + 1].x} = {@code gtextureId} (GL name; 0 on Vulkan).
 * Render thread only.
 */
public final class GuestTextureInfo {
    public static final int BINDING = 9;
    public static final int SLOT_INTS = 8;
    private static int[] data = new int[64 * SLOT_INTS];
    // Object identity: a reload closes the old GpuTexture and may recycle its GL name.
    private static GpuTexture[] written = new GpuTexture[64];
    private static int slots;
    private static int version;
    private static int ssbo;
    private static long ssboBytes;
    private static int uploaded = -1;

    private GuestTextureInfo() {
    }

    /**
     * CPU table only; any host.
     */
    public static void update(TextureManager textureManager) {
        int count = BindlessSlots.count();
        slots = count + BindlessSlots.FIRST_MATERIAL_SLOT;
        if (slots > written.length) {
            int capacity = Integer.highestOneBit(slots - 1) << 1;
            data = Arrays.copyOf(data, capacity * SLOT_INTS);
            written = Arrays.copyOf(written, capacity);
        }
        for (int i = 0; i < count; i++) {
            int slot = i + BindlessSlots.FIRST_MATERIAL_SLOT;
            AbstractTexture texture = textureManager.getTexture(BindlessSlots.key(i).texture());
            GpuTexture gpu = texture.getTextureView().texture();
            if (written[slot] == gpu) {
                continue;
            }
            int width = gpu.getWidth(0);
            int height = gpu.getHeight(0);
            boolean atlas = texture instanceof TextureAtlas;
            int base = slot * SLOT_INTS;
            data[base] = atlas ? width : 0;
            data[base + 1] = atlas ? height : 0;
            data[base + 2] = width;
            data[base + 3] = height;
            data[base + 4] = gpu instanceof GlTexture gl ? gl.glId() : 0;
            written[slot] = gpu;
            version++;
        }
    }

    /**
     * {@code [0, slots() * SLOT_INTS)} valid after {@link #update}.
     */
    public static int[] data() {
        return data;
    }

    public static int slots() {
        return slots;
    }

    /**
     * Bumps whenever {@link #update} rewrites a slot.
     */
    public static int version() {
        return version;
    }

    /**
     * GL: {@link #update}, then the raw SSBO at {@link #BINDING}.
     */
    public static void refresh(TextureManager textureManager) {
        update(textureManager);
        if (slots == BindlessSlots.FIRST_MATERIAL_SLOT || uploaded == version) {
            return;
        }
        long bytes = Math.max(Long.highestOneBit((long) slots * SLOT_INTS * Integer.BYTES - 1) << 1, 1024);
        if (ssbo == 0) {
            ssbo = GL45C.glCreateBuffers();
        }
        if (bytes > ssboBytes) {
            GL45C.glNamedBufferData(ssbo, bytes, GL15C.GL_STATIC_DRAW);
            ssboBytes = bytes;
        }
        GL45C.glNamedBufferSubData(ssbo, 0, Arrays.copyOf(data, slots * SLOT_INTS));
        uploaded = version;
    }

    public static void bind() {
        if (ssbo != 0) {
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING, ssbo);
        }
    }
}
