package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.engine.indirect.VkDepthPyramid;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorWriter;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * The HiZ inputs every VK terrain cull tests against: the per-parity UBO pair (viewProjection + camera +
 * viewport) and the terrain depth pyramid.
 */
final class VkTerrainHiZ {
    private static final long UBO_BYTES = 96;

    final VkDepthPyramid pyramid = new VkDepthPyramid();
    private final VkBuffer[] ubo;
    private boolean freshThisFrame;

    VkTerrainHiZ() {
        VkBuffer ubo0 = new VkBuffer(VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, UBO_BYTES);
        VkBuffer ubo1;
        try {
            ubo1 = new VkBuffer(VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, UBO_BYTES);
        } catch (Throwable t) {
            ubo0.delete();
            throw t;
        }
        ubo = new VkBuffer[]{ubo0, ubo1};
    }

    VkBuffer ubo(int parity) {
        return ubo[parity];
    }

    void writeFrame(ChunkRenderMatrices matrices, Minecraft mc, int parity) {
        Vec3 camPos = mc.gameRenderer.mainCamera().position();
        int cbx = Mth.floor(camPos.x);
        int cby = Mth.floor(camPos.y);
        int cbz = Mth.floor(camPos.z);
        long ptr = ubo[parity].mappedAddress();
        new Matrix4f(matrices.projection()).mul(matrices.modelView()).get(0, MemoryUtil.memByteBuffer(ptr, 64));
        MemoryUtil.memPutFloat(ptr + 64L, (float) (camPos.x - cbx));
        MemoryUtil.memPutFloat(ptr + 68L, (float) (camPos.y - cby));
        MemoryUtil.memPutFloat(ptr + 72L, (float) (camPos.z - cbz));
        RenderTarget t = mc.gameRenderer.mainRenderTarget();
        MemoryUtil.memPutFloat(ptr + 76L, (float) t.width);
        MemoryUtil.memPutInt(ptr + 80L, cbx);
        MemoryUtil.memPutInt(ptr + 84L, cby);
        MemoryUtil.memPutInt(ptr + 88L, cbz);
        MemoryUtil.memPutInt(ptr + 92L, t.height);
    }

    /**
     * terrainMode TRANSLUCENT: only the FULL path rewrites this UBO, so refresh both parity copies here -- a
     * camera-only refresh would leave the matrix frozen at the last FULL frame after a mode switch.
     */
    void writeTranslucentFrame(ChunkRenderMatrices matrices, Minecraft mc) {
        writeFrame(matrices, mc, 0);
        writeFrame(matrices, mc, 1);
        freshThisFrame = false;
    }

    void markFresh() {
        freshThisFrame = true;
    }

    void ensureFreshForTranslucentCull(GpuTextureView depthView, int width, int height, VkDescriptorWriter writer) {
        if (freshThisFrame) {
            freshThisFrame = false;
            return;
        }
        VkPrograms programs = VkPrograms.get();
        if (programs == null) {
            return;
        }
        pyramid.resize(width, height);
        long pyramidSampler = VkContext.sampler(RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        VkCommandBuffer cmd = VkContext.beginCommands();
        VkContext.pushLabel(cmd, "flywheel:vk/terrain/translucent_hiz");
        pyramid.regenerate(cmd, VkContext.imageView(depthView), pyramidSampler,
                programs.downsampleFirstPipeline(), programs.downsampleSecondPipeline(), writer);
        VkContext.popLabel(cmd);
        VkContext.submitCommands(cmd);
    }

    void delete() {
        ubo[0].delete();
        ubo[1].delete();
        pyramid.delete();
    }
}
