package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.BackendDebugFlags;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.terrain.VkTerrainDrawManager;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.vk.FlwPassBarrier;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkCmd;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.backend.vk.descriptor.VkBindlessTable;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorWriter;
import dev.engine_room.flywheel.backend.vk.shader.VkComputePipeline;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.*;

public class VkIndirectDrawManager extends DrawManager<IndirectInstancer<?>> {
    static final int COLOR_FORMAT = VK12.VK_FORMAT_R8G8B8A8_UNORM;
    static final int DEPTH_FORMAT = VK12.VK_FORMAT_D32_SFLOAT;
    static final int DRAW_COMMAND_STRIDE = (int) IndirectBuffers.DRAW_COMMAND_STRIDE;
    private static final long ZERO_BYTES = 1L << 18;
    private static final int MODEL_STRIDE = 28;
    private static final Comparator<IndirectDraw> UBER_DRAW_COMPARATOR = Comparator
            .comparingInt(IndirectDraw::bias)
            .thenComparingInt(IndirectDraw::indexOfMeshInModel)
            .thenComparing(IndirectDraw::material, MaterialRenderState::uberPipelineCompare)
            .thenComparing(IndirectDraw::material, MaterialRenderState.COMPARATOR)
            .thenComparingInt((IndirectDraw d) -> InstanceTypeIds.id(d.instanceType()));
    private static final int COPY_REGION_CAP = 256;
    final VkPrograms programs;
    final MeshPool meshPool = new MeshPool();
    final RenderPassUniforms renderPassUniforms = new RenderPassUniforms();
    final VkObjectStorage objectStorage = new VkObjectStorage();
    final FrameSet[] frames = {new FrameSet(), new FrameSet()};
    final List<UberDraw> uberOitMultiDraws = new ArrayList<>();
    final List<MeshDrawRun> meshMultiDraws = new ArrayList<>();
    final List<MeshDrawRun> meshOitMultiDraws = new ArrayList<>();
    final VkBuffer zeroBuffer = new VkBuffer(VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, ZERO_BYTES);
    final VkDescriptorWriter writer = new VkDescriptorWriter();
    final VkOitRenderer oit = new VkOitRenderer(this);
    final Matrix4f renderModelView = new Matrix4f();
    private final Map<InstanceType<?>, Group<?>> groups = new HashMap<>();
    private final List<Group<?>> frameGroups = new ArrayList<>();
    private final List<IndirectDraw> allDraws = new ArrayList<>();

    private final List<UberDraw> uberMultiDraws = new ArrayList<>();
    private final VkDepthPyramid depthPyramid = new VkDepthPyramid();
    private final VkBufferCopy.Buffer copyRegions = VkBufferCopy.calloc(COPY_REGION_CAP);
    int frameDrawCount;
    boolean lightReady;
    long cullPyramidView;
    private boolean needsDrawSort;
    @Nullable
    private IntArrayList cachedLut;
    private int lutPropagateFrames;
    private boolean carriedActive;
    private boolean pass2Pending;
    private long copySrcBuffer;
    private int copyRegionCount;
    private int frameParity;
    public VkIndirectDrawManager(VkPrograms programs) {
        this.programs = programs;
        programs.acquire();
    }

    private static boolean incompatibleUber(IndirectDraw a, IndirectDraw b) {
        if (!MaterialRenderState.uberPipelineEquals(a.material(), b.material())) {
            return true;
        }
        if (!VkCaps.BINDLESS_TEXTURES_NEGOTIATED) {
            return !a.material().texture().equals(b.material().texture())
                    || a.material().blur() != b.material().blur()
                    || a.material().mipmap() != b.material().mipmap();
        }
        return false;
    }

    static void bindGraphicsPipeline(VkCommandBuffer cmd, long handle, VkDescriptorLayout layout) {
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, handle);
        if (VkCaps.BINDLESS_TEXTURES_NEGOTIATED) {
            VkBindlessTable.bind(cmd, layout.pipelineLayout());
        }
    }

    static void drawUberIndirect(VkCommandBuffer cmd, VkBuffer drawBuffer, UberDraw batch) {
        VK12.vkCmdDrawIndexedIndirect(cmd, drawBuffer.vkBuffer(),
                (long) batch.start() * DRAW_COMMAND_STRIDE, batch.end() - batch.start(), DRAW_COMMAND_STRIDE);
    }

    boolean wantsMeshletBounds() {
        return false;
    }

    void emitMeshVisualCommands(VkCommandBuffer cmd) {
    }

    int meshVisualDstStageBits() {
        return 0;
    }

    FrameSet frame() {
        return frames[frameParity];
    }

    @Override
    protected <I extends Instance> IndirectInstancer<?> create(InstancerKey<I> key) {
        return new IndirectInstancer<>(key, new AbstractInstancer.Recreate<>(key, this), VkSlab::new);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <I extends Instance> void initialize(InstancerKey<I> key, IndirectInstancer<?> instancer) {
        Group<I> group = (Group<I>) groups.computeIfAbsent(key.type(), Group::new);
        group.add((IndirectInstancer<I>) instancer, key, meshPool);
    }

    @Override
    public void render(LightStorage lightStorage, EnvironmentStorage environmentStorage, Matrix4fc modelViewMatrix,
                       Vec3i renderOrigin, boolean constantAmbientLight) {
        super.render(lightStorage, environmentStorage, modelViewMatrix, renderOrigin, constantAmbientLight);
        renderModelView.set(modelViewMatrix);
        frameParity ^= 1;
        FrameSet fs = frames[frameParity];
        objectStorage.beginFrame(frameParity);
        pass2Pending = false; // an unconsumed pass-2 draw from a translucent-less frame is dropped, not carried
        renderPassUniforms.beginFrame(renderOrigin, constantAmbientLight);

        groups.values().removeIf(Group::flushInstancers);
        instancers.values().removeIf(instancer -> {
            if (instancer.instanceCount() > 0) {
                return false;
            }
            instancer.clear();
            return true;
        });

        meshPool.setComputeMeshletBounds(wantsMeshletBounds());
        meshPool.flush();

        if (groups.isEmpty()) {
            frameGroups.clear();
            allDraws.clear();
            uberMultiDraws.clear();
            uberOitMultiDraws.clear();
            meshMultiDraws.clear();
            meshOitMultiDraws.clear();
            return;
        }

        frameGroups.clear();
        frameGroups.addAll(groups.values());
        int modelCount = 0;
        int instanceCount = 0;
        for (Group<?> group : frameGroups) {
            for (IndirectInstancer<?> instancer : group.instancers) {
                int count = instancer.instanceCount();
                instancer.update(modelCount++, instanceCount);
                instanceCount += count;
            }
        }

        if (needsDrawSort) {
            sortDraws();
            needsDrawSort = false;
        }
        frameDrawCount = allDraws.size();

        VkCommandBuffer copyCmd = VkContext.beginCommands();
        VkContext.pushLabel(copyCmd, "flywheel:vk/instance_upload");
        long objectVkBuffer = objectStorage.objectBuffer().vkBuffer();
        for (Group<?> group : frameGroups) {
            for (IndirectInstancer<?> instancer : group.instancers) {
                instancer.uploadInstances((slab, src, dst, size) ->
                        appendCopyRegion(copyCmd, ((VkSlab) slab).vkBuffer(), objectVkBuffer, src, dst, size), true);
            }
        }
        flushCopyRegions(copyCmd, objectVkBuffer);
        VkContext.popLabel(copyCmd);
        VkContext.submitCommands(copyCmd);

        long modelBytes = (long) modelCount * MODEL_STRIDE;
        fs.model.ensureCapacity(modelBytes);
        fs.model2.ensureCapacity(modelBytes);
        long mp = fs.model.mappedAddress();

        long mp2 = fs.model2.mappedAddress();
        for (Group<?> group : frameGroups) {
            for (IndirectInstancer<?> instancer : group.instancers) {
                instancer.writeModel(mp);
                instancer.writeModel(mp2);
                mp += MODEL_STRIDE;
                mp2 += MODEL_STRIDE;
            }
        }

        long drawBytes = (long) frameDrawCount * DRAW_COMMAND_STRIDE;
        fs.draw.ensureCapacity(drawBytes);
        fs.draw2.ensureCapacity(drawBytes);
        long dp = fs.draw.mappedAddress();
        long dp2 = fs.draw2.mappedAddress();
        for (IndirectDraw draw : allDraws) {
            draw.write(dp);
            draw.write(dp2);
            dp += DRAW_COMMAND_STRIDE;
            dp2 += DRAW_COMMAND_STRIDE;
        }

        fs.indexTable.ensureCapacity((long) instanceCount * Integer.BYTES);
        fs.indexTable2.ensureCapacity((long) instanceCount * Integer.BYTES);
        fs.visWords.ensureCapacity((long) objectStorage.pageSlotCount() * 2L * Integer.BYTES);

        uploadLight(lightStorage, fs);
        MemoryUtil.memCopy(FrameUniforms.ptr(), fs.frameUbo.mappedAddress(), FrameUniforms.size());
        long matBytes = environmentStorage.arena.byteOffsetOf(environmentStorage.arena.capacity());
        fs.matrices.ensureCapacity(matBytes);
        MemoryUtil.memCopy(environmentStorage.arena.indexToPointer(0), fs.matrices.mappedAddress(), matBytes);

        objectStorage.flushDescriptors();
        dispatchCompute();
        submitOpaque(modelViewMatrix, false);
        VkTerrainDrawManager.runDeferredPhase2();
        if (carriedActive) {
            dispatchPass2Compute();
        }
    }

    private void appendCopyRegion(VkCommandBuffer cmd, long srcBuffer, long dstBuffer, long src, long dst, long size) {
        if (copyRegionCount > 0 && (srcBuffer != copySrcBuffer || copyRegionCount == COPY_REGION_CAP)) {
            flushCopyRegions(cmd, dstBuffer);
        }
        copySrcBuffer = srcBuffer;
        copyRegions.get(copyRegionCount++)
                   .set(src, dst, size);
    }

    private void flushCopyRegions(VkCommandBuffer cmd, long dstBuffer) {
        if (copyRegionCount == 0) {
            return;
        }
        copyRegions.position(0)
                   .limit(copyRegionCount);
        VK12.vkCmdCopyBuffer(cmd, copySrcBuffer, dstBuffer, copyRegions);
        copyRegions.clear();
        copyRegionCount = 0;
    }

    private void sortDraws() {
        allDraws.clear();
        for (Group<?> group : frameGroups) {
            allDraws.addAll(group.indirectDraws);
        }
        allDraws.sort(UBER_DRAW_COMPARATOR);

        uberMultiDraws.clear();
        uberOitMultiDraws.clear();
        meshMultiDraws.clear();
        meshOitMultiDraws.clear();
        int uberStart = 0;
        int meshStart = 0;
        for (int i = 0; i < allDraws.size(); i++) {
            IndirectDraw draw = allDraws.get(i);
            IndirectDraw next = i == allDraws.size() - 1 ? null : allDraws.get(i + 1);
            boolean uberSplit = next == null || incompatibleUber(draw, next);
            boolean oit = draw.material().transparency() == Transparency.ORDER_INDEPENDENT;
            if (uberSplit || draw.instanceType() != next.instanceType()) {
                (oit ? meshOitMultiDraws : meshMultiDraws).add(
                        new MeshDrawRun(draw.material(), draw.instanceType(), meshStart, i + 1));
                meshStart = i + 1;
            }
            if (uberSplit) {
                (oit ? uberOitMultiDraws : uberMultiDraws).add(new UberDraw(draw.material(), uberStart, i + 1));
                uberStart = i + 1;
            }
        }
    }

    void warmTextures(TextureManager textureManager) {
        for (UberDraw multiDraw : uberMultiDraws) {
            textureManager.getTexture(multiDraw.material().texture());
        }
        for (UberDraw multiDraw : uberOitMultiDraws) {
            textureManager.getTexture(multiDraw.material().texture());
        }
    }

    private void dispatchPass2Compute() {
        VkCommandBuffer cmd = VkContext.beginCommands();
        VkContext.pushLabel(cmd, "flywheel:vk/instance_pass2");
        long pyramidSampler = VkContext.sampler(RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        dispatchCullPass(cmd, pyramidSampler, programs.cullPass2Pipeline(), true);
        VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT);
        dispatchApplyPass(cmd, true);
        VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK12.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK12.VK_ACCESS_SHADER_READ_BIT);
        VkContext.popLabel(cmd);
        VkContext.submitCommands(cmd);
        pass2Pending = true;
    }

    private void dispatchCullPass(VkCommandBuffer cmd, long pyramidSampler, VkComputePipeline pipeline, boolean pass2) {
        FrameSet fs = frame();
        VkContext.pushLabel(cmd, "flywheel:vk/instance_cull");
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.handle());
        writer.storage(0, objectStorage.frameDescriptorBuffer())
              .storage(1, objectStorage.objectBuffer())
              .storage(2, pass2 ? fs.indexTable2 : fs.indexTable)
              .storage(3, pass2 ? fs.model2 : fs.model)
              .storage(6, fs.visWords)
              .storage(7, fs.matrices)
              .uniform(16, fs.frameUbo)
              .uniform(17, fs.frameUbo)
              .uniform(18, fs.frameUbo)
              .uniform(19, fs.frameUbo)
              .sampler(10, cullPyramidView, pyramidSampler);
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.layout());
        VK12.vkCmdDispatch(cmd, objectStorage.pageSlotCount(), 1, 1);
        VkContext.popLabel(cmd);
    }

    private void dispatchApplyPass(VkCommandBuffer cmd, boolean pass2) {
        FrameSet fs = frame();
        VkContext.pushLabel(cmd, "flywheel:vk/instance_apply");
        VkComputePipeline pipeline = programs.applyPipeline();
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.handle());
        writer.storage(3, pass2 ? fs.model2 : fs.model).storage(4, pass2 ? fs.draw2 : fs.draw);
        writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.layout());
        VK12.vkCmdDispatch(cmd, Mth.positiveCeilDiv(frameDrawCount, VkCaps.SUBGROUP_SIZE), 1, 1);
        VkContext.popLabel(cmd);
    }

    private void drawPass2IfPending() {
        if (!pass2Pending) {
            return;
        }
        pass2Pending = false;
        submitOpaque(renderModelView, true);
    }

    private void dispatchCompute() {
        VkCommandBuffer cmd = VkContext.beginCommands();
        VkContext.pushLabel(cmd, "flywheel:vk/instance_compute");
        long pyramidSampler = VkContext.sampler(RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));

        VkContext.pushLabel(cmd, "flywheel:vk/hiz");
        generatePyramid(cmd, pyramidSampler);
        VkContext.popLabel(cmd);

        VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK12.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | meshVisualDstStageBits(),
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT);

        dispatchCullPass(cmd, pyramidSampler, programs.cullPipeline(), false);
        VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT);
        dispatchApplyPass(cmd, false);
        emitMeshVisualCommands(cmd);
        VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK12.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | meshVisualDstStageBits(),
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK12.VK_ACCESS_SHADER_READ_BIT);
        VkContext.popLabel(cmd);
        VkContext.submitCommands(cmd);
    }

    private void generatePyramid(VkCommandBuffer cmd, long sampler) {
        long carried = VkTerrainDrawManager.takeCarriedPyramidView();
        carriedActive = carried != 0L;
        if (carried != 0L) {
            cullPyramidView = carried;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        depthPyramid.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
        cullPyramidView = depthPyramid.sampledView();

        GpuTextureView depthTexView = mc.gameRenderer.mainRenderTarget().getDepthTextureView();
        if (depthTexView == null) {
            return;
        }
        depthPyramid.regenerate(cmd, VkContext.imageView(depthTexView), sampler, programs.downsampleFirstPipeline(),
                programs.downsampleSecondPipeline(), writer);
    }

    private void submitOpaque(Matrix4fc modelViewMatrix, boolean pass2) {
        GpuBuffer vertexBuffer = meshPool.vertexBuffer();
        GpuBuffer indexBuffer = meshPool.indexBuffer();
        if (vertexBuffer == null || indexBuffer == null) {
            return;
        }

        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        GpuBufferSlice lights = RenderSystem.getShaderLights();
        GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
        if (projection == null || fog == null || lights == null || globals == null) {
            return;
        }
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                                                       .writeTransform(new Matrix4f(modelViewMatrix));
        GpuBufferSlice renderOriginSlice = renderPassUniforms.renderOriginSlice();

        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return;
        }
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();

        TextureManager textureManager = mc.getTextureManager();
        long overlayView = VkContext.imageView(mc.gameRenderer.overlayTexture().getTextureView());
        long lightmapView = VkContext.imageView(mc.gameRenderer.lightmap());
        long overlaySampler = VkContext.sampler(RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));

        long vertexVk = VkContext.buffer(vertexBuffer);
        long indexVk = VkContext.buffer(indexBuffer);

        warmTextures(textureManager);
        if (VkCaps.BINDLESS_TEXTURES_NEGOTIATED) {
            VkBindlessTable.refresh(textureManager);
            VkBindlessTable.setReserved(VkBindlessTable.SLOT_OVERLAY, overlayView, overlaySampler);
            VkBindlessTable.setReserved(VkBindlessTable.SLOT_LIGHTMAP, lightmapView, overlaySampler);
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        FlwPassBarrier.expectFramebufferProducer();
        try (RenderPass pass = encoder.createRenderPass(
                pass2 ? () -> "flywheel:vk/opaque_pass2" : () -> "flywheel:vk/opaque",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            VkCommandBuffer cmd = ((VulkanRenderPass) pass.backend).commandBuffer;
            VkCmd.setViewportScissor(cmd, width, height);

            VK12.vkCmdBindIndexBuffer(cmd, indexVk, 0L, VK12.VK_INDEX_TYPE_UINT32);
            VkCmd.bindVertexBuffer(cmd, vertexVk);

            VkContext.pushLabel(cmd, pass2 ? "flywheel:vk/opaque_pass2" : "flywheel:vk/opaque");
            drawSolid(cmd, projection, dynamicTransforms, fog, lights, globals, renderOriginSlice,
                    textureManager, overlayView, overlaySampler, lightmapView, pass2);
            VkContext.popLabel(cmd);
        } finally {
            FlwPassBarrier.clear();
        }
    }

    private void uploadLight(LightStorage light, FrameSet fs) {
        int bytes = light.sectionDataBytes();
        if (bytes == 0) {
            lightReady = false;
            return;
        }
        fs.lightSections.ensureCapacity(bytes);
        if (!light.changed.isEmpty()) {
            frames[0].lightPending.or(light.changed);
            frames[1].lightPending.or(light.changed);
            light.changed.clear();
        }
        BitSet pending = fs.lightPending;
        if (!pending.isEmpty()) {
            long dstBase = fs.lightSections.mappedAddress();
            for (int i = pending.nextSetBit(0); i >= 0; i = pending.nextSetBit(i + 1)) {
                MemoryUtil.memCopy(light.arena.indexToPointer(i),
                        dstBase + (long) i * LightStorage.SECTION_SIZE_BYTES, LightStorage.SECTION_SIZE_BYTES);
            }
            pending.clear();
        }
        if (light.checkNeedsLutRebuildAndClear()) {
            cachedLut = light.createLut();
            lutPropagateFrames = 2;
        }
        if (lutPropagateFrames > 0 && cachedLut != null) {
            int size = cachedLut.size();
            fs.lightLut.ensureCapacity((long) size * Integer.BYTES);
            long ptr = fs.lightLut.mappedAddress();
            for (int i = 0; i < size; i++) {
                MemoryUtil.memPutInt(ptr + (long) i * Integer.BYTES, cachedLut.getInt(i));
            }
            lutPropagateFrames--;
        }
        lightReady = true;
    }

    void writeAtlasTrio(TextureManager textureManager, Identifier texture, long atlasSampler,
                        long overlayView, long overlaySampler, long lightmapView) {
        long atlasView = VkContext.imageView(textureManager.getTexture(texture).getTextureView());
        writer.sampler(10, atlasView, atlasSampler)
              .sampler(11, overlayView, overlaySampler)
              .sampler(12, lightmapView, overlaySampler);
    }

    void writeUberCommon(FrameSet fs, VkBuffer indexTable, VkBuffer drawBuffer, int batchStart) {
        writer.storage(1, objectStorage.objectBuffer())
              .storage(2, indexTable)
              .storage(4, drawBuffer)
              .storage(7, fs.matrices)
              .uniform(21, renderPassUniforms.material(0))
              .uniform(23, renderPassUniforms.embedDraw(batchStart));
    }

    void writeLight(FrameSet fs) {
        writer.storage(5, lightReady ? fs.lightLut : zeroBuffer)
              .storage(6, lightReady ? fs.lightSections : zeroBuffer);
    }

    void writeLightFog(FrameSet fs, GpuBufferSlice fog, GpuBufferSlice lights, GpuBuffer globals,
                       GpuBufferSlice renderOrigin) {
        writeLight(fs);
        writer.uniform(18, fog)
              .uniform(19, lights)
              .uniform(20, globals)
              .uniform(22, renderOrigin);
    }

    void drawOitProducerGeometry(VkCommandBuffer cmd, OitMode mode, VkOitRenderer.OitFrame f, boolean folded) {
        if (uberOitMultiDraws.isEmpty()) {
            return;
        }
        FrameSet fs = frame();
        var smoothness = BackendConfig.INSTANCE.lightSmoothness();
        boolean needsColor = mode != OitMode.DEPTH_RANGE;
        boolean bindless = VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
        VkGraphicsPipeline lastPipeline = null;
        for (UberDraw multiDraw : uberOitMultiDraws) {
            Material material = multiDraw.material();
            VkGraphicsPipeline pipeline = programs.uber().oitProducerPipeline(material, smoothness, mode, folded);
            if (pipeline != lastPipeline) {
                bindGraphicsPipeline(cmd, pipeline.handle(), pipeline.layout());
                lastPipeline = pipeline;
            }
            writeUberCommon(fs, fs.indexTable, fs.draw, multiDraw.start());
            writer.uniform(16, f.projection()).uniform(17, f.dynamicTransforms());
            if (needsColor) {
                if (!bindless) {
                    writeAtlasTrio(f.textureManager(), material.texture(),
                            VkContext.sampler(MaterialSamplers.get(material)),
                            f.overlayView(), f.overlaySampler(), f.lightmapView());
                    writer.sampler(15, f.blueNoiseView(), f.blueNoiseSampler());
                }
                writeLightFog(fs, f.fog(), f.lights(), f.globals(), f.renderOriginSlice());
                VkWaveletOitChain.writeOitReads(writer, f, mode, folded);
            }
            writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            drawUberIndirect(cmd, fs.draw, multiDraw);
        }
    }

    void drawMlabProducerGeometry(OitInsertMode oitMode, VkCommandBuffer cmd, VkOitRenderer.OitFrame f,
                                  VkMlabBuffers mlab) {
        if (uberOitMultiDraws.isEmpty()) {
            return;
        }
        FrameSet fs = frame();
        var smoothness = BackendConfig.INSTANCE.lightSmoothness();
        boolean bindless = VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
        VkGraphicsPipeline lastPipeline = null;
        for (UberDraw multiDraw : uberOitMultiDraws) {
            Material material = multiDraw.material();
            VkGraphicsPipeline pipeline = programs.uber().mlabProducerPipeline(oitMode, material, smoothness);
            if (pipeline != lastPipeline) {
                bindGraphicsPipeline(cmd, pipeline.handle(), pipeline.layout());
                lastPipeline = pipeline;
            }
            writeUberCommon(fs, fs.indexTable, fs.draw, multiDraw.start());
            writer.uniform(16, f.projection()).uniform(17, f.dynamicTransforms());
            if (!bindless) {
                writeAtlasTrio(f.textureManager(), material.texture(),
                        VkContext.sampler(MaterialSamplers.get(material)),
                        f.overlayView(), f.overlaySampler(), f.lightmapView());
            }
            writeLightFog(fs, f.fog(), f.lights(), f.globals(), f.renderOriginSlice());
            mlab.bind(writer);
            writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            drawUberIndirect(cmd, fs.draw, multiDraw);
        }
    }

    void drawSolid(VkCommandBuffer cmd, GpuBufferSlice projection, GpuBufferSlice dynamicTransforms,
                   GpuBufferSlice fog, GpuBufferSlice lights, GpuBuffer globals, GpuBufferSlice renderOriginSlice,
                   TextureManager textureManager, long overlayView, long overlaySampler, long lightmapView,
                   boolean pass2) {
        drawVertexPath(cmd, projection, dynamicTransforms, fog, lights, globals, renderOriginSlice,
                textureManager, overlayView, overlaySampler, lightmapView, pass2);
    }

    private void drawVertexPath(VkCommandBuffer cmd, GpuBufferSlice projection, GpuBufferSlice dynamicTransforms,
                                GpuBufferSlice fog, GpuBufferSlice lights, GpuBuffer globals,
                                GpuBufferSlice renderOriginSlice,
                                TextureManager textureManager, long overlayView, long overlaySampler, long lightmapView,
                                boolean pass2) {
        if (uberMultiDraws.isEmpty()) {
            return;
        }
        FrameSet fs = frame();
        VkBuffer indexTable = pass2 ? fs.indexTable2 : fs.indexTable;
        VkBuffer drawBuffer = pass2 ? fs.draw2 : fs.draw;
        var smoothness = BackendConfig.INSTANCE.lightSmoothness();
        boolean bindless = VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
        VkGraphicsPipeline lastPipeline = null;
        for (UberDraw multiDraw : uberMultiDraws) {
            Material material = multiDraw.material();
            VkGraphicsPipeline pipeline = programs.uber()
                                                  .drawPipeline(material, smoothness, COLOR_FORMAT, DEPTH_FORMAT);
            if (pipeline != lastPipeline) {
                bindGraphicsPipeline(cmd, pipeline.handle(), pipeline.layout());
                lastPipeline = pipeline;
            }

            if (!bindless) {
                writeAtlasTrio(textureManager, material.texture(), VkContext.sampler(MaterialSamplers.get(material)),
                        overlayView, overlaySampler, lightmapView);
            }
            writeUberCommon(fs, indexTable, drawBuffer, multiDraw.start());
            writer.uniform(16, projection).uniform(17, dynamicTransforms);
            writeLightFog(fs, fog, lights, globals, renderOriginSlice);
            writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());

            drawUberIndirect(cmd, drawBuffer, multiDraw);
        }
    }

    @Override
    public boolean renderOit(LightStorage lightStorage, EnvironmentStorage environmentStorage,
                             @Nullable ChunkSectionsToRender chunks, @Nullable BerTranslucentCapture ber,
                             @Nullable SodiumTerrainOitReplay terrain, @Nullable FabulousCaptures fabulous) {
        drawPass2IfPending();
        if (BackendDebugFlags.SKIP_OIT) {
            return false;
        }
        return oit.render(chunks, ber, terrain, fabulous);
    }

    @Override
    public void renderCrumbling(List<Engine.CrumblingBlock> crumblingBlocks) {
        var byType = doCrumblingSort(crumblingBlocks, IndirectInstancer::fromState);
        if (byType.isEmpty()) {
            return;
        }
        GpuBuffer vertexBuffer = meshPool.vertexBuffer();
        GpuBuffer indexBuffer = meshPool.indexBuffer();
        if (vertexBuffer == null || indexBuffer == null) {
            return;
        }
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        GpuBufferSlice lights = RenderSystem.getShaderLights();
        GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
        if (projection == null || fog == null || lights == null || globals == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return;
        }
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                                                       .writeTransform(new Matrix4f(renderModelView));
        GpuBufferSlice renderOriginSlice = renderPassUniforms.renderOriginSlice();
        var smoothness = BackendConfig.INSTANCE.lightSmoothness();
        FrameSet fs = frame();

        TextureManager tm = mc.getTextureManager();
        long atlasSampler = VkContext.sampler(RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true));
        long loSampler = VkContext.sampler(RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        long crackSampler = VkContext.sampler(RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
        long overlayView = VkContext.imageView(mc.gameRenderer.overlayTexture().getTextureView());
        long lightmapView = VkContext.imageView(mc.gameRenderer.lightmap());
        long vertexVk = VkContext.buffer(vertexBuffer);
        long indexVk = VkContext.buffer(indexBuffer);
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();

        SimpleMaterial.Builder crumblingMaterial = SimpleMaterial.builder();

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        FlwPassBarrier.expectFramebufferProducer();
        try (RenderPass pass = encoder.createRenderPass(() -> "flywheel:vk/crumbling",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            VkCommandBuffer cmd = ((VulkanRenderPass) pass.backend).commandBuffer;
            VkCmd.setViewportScissor(cmd, width, height);
            VK12.vkCmdBindIndexBuffer(cmd, indexVk, 0L, VK12.VK_INDEX_TYPE_UINT32);
            VkCmd.bindVertexBuffer(cmd, vertexVk);

            VkContext.pushLabel(cmd, "flywheel:vk/crumbling");
            VkBuffer objectBuffer = objectStorage.objectBuffer();
            CrumblingFrame cf = new CrumblingFrame(fs, objectBuffer, tm, crackSampler, overlayView, lightmapView,
                    loSampler, projection, dynamicTransforms, fog, lights, renderOriginSlice, vertexVk, indexVk);
            for (var groupEntry : byType.entrySet()) {
                InstanceType<?> instanceType = groupEntry.getKey().instanceType();

                for (var progressEntry : groupEntry.getValue().int2ObjectEntrySet()) {
                    long crackView = VkContext.imageView(tm.getTexture(
                            ModelBakery.BREAKING_LOCATIONS.get(progressEntry.getIntKey())).getTextureView());

                    for (var pair : progressEntry.getValue()) {
                        IndirectInstancer<?> instancer = pair.getFirst();
                        int firstInstance = instancer.local2ObjectUintOffset(pair.getSecond().index);

                        for (IndirectDraw draw : instancer.draws()) {
                            var mesh = draw.mesh();
                            if (mesh.isInvalid()) {
                                continue;
                            }
                            CommonCrumbling.applyCrumblingProperties(crumblingMaterial, draw.material());
                            if (drawMeshCrumbling(cmd, cf, instanceType, draw, firstInstance, crumblingMaterial,
                                    crackView)) {
                                continue;
                            }
                            VkGraphicsPipeline pipeline = programs.uber().crumblingPipeline(instanceType, smoothness,
                                    COLOR_FORMAT, DEPTH_FORMAT);
                            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());

                            writer.storage(1, objectBuffer);
                            writeLight(fs);
                            writeAtlasTrio(tm, draw.material().texture(), atlasSampler, overlayView, loSampler,
                                    lightmapView);
                            writer.sampler(13, crackView, crackSampler)
                                  .uniform(16, projection)
                                  .uniform(17, dynamicTransforms)
                                  .uniform(18, fog)
                                  .uniform(19, lights)
                                  .uniform(20, globals)
                                  .uniform(21, renderPassUniforms.material(
                                          MaterialEncoder.packProperties(crumblingMaterial)))
                                  .uniform(22, renderOriginSlice);
                            writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());

                            VK12.vkCmdDrawIndexed(cmd, mesh.indexCount(), 1, mesh.firstIndex(), mesh.baseVertex(),
                                    firstInstance);
                        }
                    }
                }
            }
            VkContext.popLabel(cmd);
        } finally {
            FlwPassBarrier.clear();
        }
    }

    boolean drawMeshCrumbling(VkCommandBuffer cmd, CrumblingFrame cf, InstanceType<?> instanceType, IndirectDraw draw,
                              int objectSlot, SimpleMaterial.Builder crumblingMaterial, long crackView) {
        return false;
    }

    @Override
    public void triggerFallback() {
        FlwBackend.LOGGER.error("flywheel:vk_indirect requested a fallback");
    }

    @Override
    public MeshPool meshPool() {
        return meshPool;
    }

    @Override
    public void delete() {
        instancers.values()
                  .forEach(IndirectInstancer::delete);

        super.delete();
        groups.clear();
        frameGroups.clear();
        objectStorage.delete();
        meshPool.delete();
        renderPassUniforms.delete();
        zeroBuffer.delete();
        copyRegions.free();
        frames[0].delete();
        frames[1].delete();
        depthPyramid.delete();
        oit.delete();
        programs.release();
    }

    record UberDraw(Material material, int start, int end) {
    }

    record MeshDrawRun(Material material, InstanceType<?> type, int start, int end) {
    }

    static final class FrameSet {
        final VkBuffer indexTable = storage(256);                 // binding 2: written by the cull
        final VkBuffer model = storage(256);                      // binding 3: cull-zeroed + apply-read
        final VkBuffer draw = indirectStorage(256);               // binding 4
        final VkBuffer visWords = storage(
                256);                   // binding 6: 2 words per page slot: [2w]=drawn, [2w+1]=frustum-passed
        final VkBuffer indexTable2 = storage(256);
        final VkBuffer model2 = storage(256);
        final VkBuffer draw2 = indirectStorage(256);
        // Section-light SSBOs (bindings 5/6): changes fold into BOTH parities so they propagate over two frames, changed-only.
        final VkBuffer lightLut = storage(1L << 12);
        final VkBuffer lightSections = storage(1L << 16);
        final BitSet lightPending = new BitSet();
        // Frame UBO (binding 16): single-buffered would race the next frame's overwrite -> corrupted frustum.
        final VkBuffer frameUbo = new VkBuffer(VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, FrameUniforms.size());
        // Embedded pose matrices SSBO (binding 7), host-copied; slot 0 is the reserved identity, so matrixIndex 0
        // never indexes past it. The cull reads them too, so the copy must be complete before dispatchCompute.
        final VkBuffer matrices = storage(1L << 12);
        // Mesh-visual tier: per-parity VkDrawMeshTasksIndirectCommandEXT stream (binding 15), allocated lazily.
        @Nullable
        VkBuffer meshTaskCommands;
        // The per-frame render-origin modelview UBO (binding 9, _FlwMeshVisualFrame) the mesh + fragment share.
        @Nullable
        VkBuffer meshVisualModelView;

        private static VkBuffer storage(long size) {
            return new VkBuffer(VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, size);
        }

        private static VkBuffer indirectStorage(long size) {
            return new VkBuffer(VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,
                    size);
        }

        void delete() {
            indexTable.delete();
            model.delete();
            draw.delete();
            visWords.delete();
            indexTable2.delete();
            model2.delete();
            draw2.delete();
            if (meshTaskCommands != null) {
                meshTaskCommands.delete();
            }
            lightLut.delete();
            lightSections.delete();
            frameUbo.delete();
            matrices.delete();
            if (meshVisualModelView != null) {
                meshVisualModelView.delete();
            }
        }
    }

    record CrumblingFrame(FrameSet fs, VkBuffer objectBuffer, TextureManager tm, long crackSampler, long overlayView,
                          long lightmapView, long loSampler, GpuBufferSlice projection,
                          GpuBufferSlice dynamicTransforms,
                          GpuBufferSlice fog, GpuBufferSlice lights, GpuBufferSlice renderOriginSlice,
                          long vertexVk, long indexVk) {
    }

    private final class Group<I extends Instance> {
        private final InstanceType<I> type;
        private final List<IndirectInstancer<I>> instancers = new ArrayList<>();
        private final List<IndirectDraw> indirectDraws = new ArrayList<>();

        Group(InstanceType<I> type) {
            this.type = type;
        }

        void add(IndirectInstancer<I> instancer, InstancerKey<I> key, MeshPool meshPool) {
            instancer.mapping = objectStorage.createMapping(type);
            instancer.update(instancers.size(), -1);
            instancers.add(instancer);
            List<Model.ConfiguredMesh> meshes = key.model().meshes();
            for (int i = 0; i < meshes.size(); i++) {
                Model.ConfiguredMesh entry = meshes.get(i);
                MeshPool.PooledMesh mesh = meshPool.alloc(entry.mesh());
                IndirectDraw draw = new IndirectDraw(instancer, entry.material(), mesh, key.bias(), i);
                indirectDraws.add(draw);
                instancer.addDraw(draw);
            }
            needsDrawSort = true;
        }

        boolean flushInstancers() {
            for (var it = instancers.iterator(); it.hasNext(); ) {
                IndirectInstancer<I> instancer = it.next();
                if (instancer.instanceCount() == 0) {
                    it.remove();
                    instancer.delete();
                }
            }
            if (indirectDraws.removeIf(IndirectDraw::deleted)) {
                needsDrawSort = true;
            }
            return indirectDraws.isEmpty();
        }
    }
}
