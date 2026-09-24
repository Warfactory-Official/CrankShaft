package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.backend.compile.*;
import dev.engine_room.flywheel.backend.engine.MaterialEncoder;
import dev.engine_room.flywheel.backend.engine.MaterialSamplers;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkCmd;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.backend.vk.shader.VkComputePipeline;
import dev.engine_room.flywheel.backend.vk.shader.VkMeshPipeline;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

/**
 * The vk_mesh_shader VISUAL DrawManager: solid + OIT draws go through vkCmdDrawMeshTasksIndirectEXT (per-type EXT mesh pipelines over the same cull/apply output); crumbling via the EXT crumbling mesh variant.
 */
public final class VkMeshVisualDrawManager extends VkIndirectDrawManager {
    public VkMeshVisualDrawManager(VkPrograms programs) {
        super(programs);
    }

    private static void pushMeshVisualConstants(VkCommandBuffer cmd, VkMeshPipeline pipeline, MeshVisualInputs in,
                                                int baseDraw) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long pc = stack.nmalloc(VkMeshVisualPipelines.PUSH_BYTES);
            MemoryUtil.memPutLong(pc, in.vertsAddr());
            MemoryUtil.memPutLong(pc + 8L, in.indicesAddr());
            MemoryUtil.memPutLong(pc + 16L, in.boundsAddr());
            MemoryUtil.memPutInt(pc + 24L, baseDraw);
            VK10.nvkCmdPushConstants(cmd, pipeline.layout().pipelineLayout(),
                    EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT | EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT,
                    0, VkMeshVisualPipelines.PUSH_BYTES, pc);
        }
    }

    private static void drawMeshTasksIndirect(VkCommandBuffer cmd, VkBuffer commands, MeshDrawRun run) {
        EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, commands.vkBuffer(),
                (long) run.start() * VkMeshVisualPipelines.COMMAND_STRIDE, run.end() - run.start(),
                VkMeshVisualPipelines.COMMAND_STRIDE);
    }

    private VkMeshVisualPipelines meshVisualPipelines() {
        return programs.meshVisual();
    }

    @Override
    boolean wantsMeshletBounds() {
        return true;
    }

    @Override
    int meshVisualDstStageBits() {
        return EXTMeshShader.VK_PIPELINE_STAGE_TASK_SHADER_BIT_EXT | EXTMeshShader.VK_PIPELINE_STAGE_MESH_SHADER_BIT_EXT;
    }

    @Override
    void emitMeshVisualCommands(VkCommandBuffer cmd) {
        FrameSet fs = frame();
        if (fs.meshVisualModelView == null) {
            fs.meshVisualModelView = new VkBuffer(VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                    MeshVisualShaders.FRAME_UBO_BYTES);
        }
        long framePtr = fs.meshVisualModelView.mappedAddress();
        renderModelView.get(0, MemoryUtil.memByteBuffer(framePtr, 64));
        MemoryUtil.memPutFloat(framePtr + 64L, (float) ((double) (System.nanoTime() / 1000000L) / 1000.0));
        MemoryUtil.memPutFloat(framePtr + 68L, Minecraft.getInstance().options.glintSpeed()
                                                                              .get()
                                                                              .floatValue());
        MemoryUtil.memPutFloat(framePtr + 72L, Minecraft.getInstance().options.glintStrength()
                                                                              .get()
                                                                              .floatValue());
        MemoryUtil.memPutFloat(framePtr + 76L, FrameUniforms.partialTick());

        int n = frameDrawCount;
        if (n == 0) {
            return;
        }
        VkCmd.memoryBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
        VkComputePipeline pipeline = meshVisualPipelines().builderPipeline();
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.handle());
        VkContext.pushLabel(cmd, "flywheel:vk/mesh_visual_emit");
        long bytes = (long) n * VkMeshVisualPipelines.COMMAND_STRIDE;
        if (fs.meshTaskCommands == null) {
            fs.meshTaskCommands = new VkBuffer(
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, bytes);
        } else {
            fs.meshTaskCommands.ensureCapacity(bytes);
        }
        writer.storage(4, fs.draw).storage(15, fs.meshTaskCommands);
        writer.flush(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.layout());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long pc = stack.nmalloc(Integer.BYTES);
            MemoryUtil.memPutInt(pc, n);
            VK10.nvkCmdPushConstants(cmd, pipeline.layout().pipelineLayout(), VK10.VK_SHADER_STAGE_COMPUTE_BIT,
                    0, Integer.BYTES, pc);
        }
        VK10.vkCmdDispatch(cmd, Mth.positiveCeilDiv(n, 64), 1, 1);
        VkContext.popLabel(cmd);
    }

    @Nullable
    private MeshVisualInputs meshVisualInputs() {
        FrameSet fs = frame();
        VkBuffer cmds = fs.meshTaskCommands;
        GpuBuffer poolVerts = meshPool.vertexBuffer();
        GpuBuffer poolIndices = meshPool.indexBuffer();
        VkBuffer mvUbo = fs.meshVisualModelView;
        if (cmds == null || poolVerts == null || poolIndices == null || mvUbo == null) {
            return null;
        }
        GpuBuffer bounds = meshPool.meshletBounds();
        return new MeshVisualInputs(cmds,
                VkMeshVisualPipelines.deviceAddress(VkContext.buffer(poolVerts)),
                VkMeshVisualPipelines.deviceAddress(VkContext.buffer(poolIndices)),
                bounds == null ? 0L : VkMeshVisualPipelines.deviceAddress(VkContext.buffer(bounds)),
                mvUbo);
    }

    private void writeMeshVisualCommon(FrameSet fs, MeshVisualInputs in, long pyramidSampler) {
        writer.storage(1, objectStorage.objectBuffer())
              .storage(2, fs.indexTable)
              .storage(4, fs.draw)
              .storage(7, fs.matrices)
              .sampler(23, cullPyramidView, pyramidSampler)
              .uniform(8, fs.frameUbo)
              .uniform(9, in.modelViewUbo());
    }

    // ONE EXT mesh pipeline per instance type (material state via the command's packedMaterialProperties); per run only the pipeline/atlas + baseDraw push change.
    @Override
    void drawSolid(VkCommandBuffer cmd, GpuBufferSlice projection, GpuBufferSlice dynamicTransforms,
                   GpuBufferSlice fog, GpuBufferSlice lights, GpuBuffer globals, GpuBufferSlice renderOriginSlice,
                   TextureManager textureManager, long overlayView, long overlaySampler, long lightmapView,
                   boolean pass2) {
        if (pass2) {
            super.drawSolid(cmd, projection, dynamicTransforms, fog, lights, globals, renderOriginSlice,
                    textureManager, overlayView, overlaySampler, lightmapView, true);
            return;
        }
        if (meshMultiDraws.isEmpty()) {
            return;
        }
        MeshVisualInputs in = meshVisualInputs();
        if (in == null) {
            return;
        }
        FrameSet fs = frame();
        long pyramidSampler = VkContext.sampler(RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));

        boolean bindless = VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
        VkMeshPipeline lastPipeline = null;

        for (MeshDrawRun multiDraw : meshMultiDraws) {
            Material material = multiDraw.material();
            VkMeshPipeline pipeline = meshVisualPipelines().solidPipeline(multiDraw.type(), material,
                    multiDraw.embedded(), COLOR_FORMAT, DEPTH_FORMAT);
            if (pipeline != lastPipeline) {
                bindGraphicsPipeline(cmd, pipeline.handle(), pipeline.layout());
                lastPipeline = pipeline;
            }
            if (!bindless) {
                writeAtlasTrio(textureManager, material.texture(), VkContext.sampler(MaterialSamplers.get(material)),
                        overlayView, overlaySampler, lightmapView);
            }
            writeMeshVisualCommon(fs, in, pyramidSampler);
            writeLight(fs);
            writer.uniform(16, projection)
                  .uniform(17, dynamicTransforms)
                  .uniform(18, fog)
                  .uniform(19, lights)
                  .uniform(22, renderOriginSlice);
            writer.flush(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            pushMeshVisualConstants(cmd, pipeline, in, multiDraw.start());

            drawMeshTasksIndirect(cmd, in.commands(), multiDraw);
        }
    }

    @Override
    void drawOitProducerGeometry(VkCommandBuffer cmd, OitMode mode, VkOitRenderer.OitFrame f, boolean folded,
                                 boolean additive) {
        List<MeshDrawRun> draws = additive ? meshOitAdditiveMultiDraws : meshOitMultiDraws;
        if (draws.isEmpty()) {
            return;
        }
        MeshVisualInputs in = meshVisualInputs();
        if (in == null) {
            return;
        }
        FrameSet fs = frame();
        long pyramidSampler = VkContext.sampler(RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        boolean needsColor = mode != OitMode.DEPTH_RANGE;
        boolean bindless = VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
        VkMeshPipeline lastPipeline = null;

        for (MeshDrawRun multiDraw : draws) {
            VkMeshPipeline pipeline = meshVisualPipelines().oitPipeline(multiDraw.type(), multiDraw.material(),
                    multiDraw.embedded(), mode, DEPTH_FORMAT, folded);
            if (pipeline != lastPipeline) {
                bindGraphicsPipeline(cmd, pipeline.handle(), pipeline.layout());
                lastPipeline = pipeline;
            }
            writeMeshVisualCommon(fs, in, pyramidSampler);
            writer.uniform(16, f.projection()).uniform(17, f.dynamicTransforms());
            if (needsColor) {
                Material material = multiDraw.material();
                if (!bindless) {
                    writeAtlasTrio(f.textureManager(), material.texture(),
                            VkContext.sampler(MaterialSamplers.get(material)),
                            f.overlayView(), f.overlaySampler(), f.lightmapView());
                    writer.sampler(15, f.blueNoiseView(), f.blueNoiseSampler());
                }
                writeLight(fs);
                writer.uniform(18, f.fog()).uniform(19, f.lights()).uniform(22, f.renderOriginSlice());
                VkWaveletOitChain.writeOitReads(writer, f, mode, folded);
            }
            writer.flush(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            pushMeshVisualConstants(cmd, pipeline, in, multiDraw.start());

            drawMeshTasksIndirect(cmd, in.commands(), multiDraw);
        }
    }

    @Override
    void drawMlabProducerGeometry(OitInsertMode oitMode, VkCommandBuffer cmd, VkOitRenderer.OitFrame f,
                                  VkMlabBuffers mlab) {
        drawMlabProducerGeometry(oitMode, cmd, f, mlab, meshOitMultiDraws);
        drawMlabProducerGeometry(oitMode, cmd, f, mlab, meshOitAdditiveMultiDraws);
    }

    private void drawMlabProducerGeometry(OitInsertMode oitMode, VkCommandBuffer cmd, VkOitRenderer.OitFrame f,
                                          VkMlabBuffers mlab, List<MeshDrawRun> draws) {
        if (draws.isEmpty()) {
            return;
        }
        MeshVisualInputs in = meshVisualInputs();
        if (in == null) {
            return;
        }
        FrameSet fs = frame();
        long pyramidSampler = VkContext.sampler(RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        boolean bindless = VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
        VkMeshPipeline lastPipeline = null;

        for (MeshDrawRun multiDraw : draws) {
            VkMeshPipeline pipeline = meshVisualPipelines().mlabPipeline(multiDraw.type(), multiDraw.material(),
                    multiDraw.embedded(), oitMode, DEPTH_FORMAT);
            if (pipeline != lastPipeline) {
                bindGraphicsPipeline(cmd, pipeline.handle(), pipeline.layout());
                lastPipeline = pipeline;
            }
            writeMeshVisualCommon(fs, in, pyramidSampler);
            writer.uniform(16, f.projection()).uniform(17, f.dynamicTransforms());
            Material material = multiDraw.material();
            if (!bindless) {
                writeAtlasTrio(f.textureManager(), material.texture(),
                        VkContext.sampler(MaterialSamplers.get(material)),
                        f.overlayView(), f.overlaySampler(), f.lightmapView());
            }
            writeLight(fs);
            writer.uniform(18, f.fog()).uniform(19, f.lights()).uniform(22, f.renderOriginSlice());
            mlab.bind(writer);
            writer.flush(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            pushMeshVisualConstants(cmd, pipeline, in, multiDraw.start());

            drawMeshTasksIndirect(cmd, in.commands(), multiDraw);
        }
    }

    @Override
    boolean drawMeshCrumbling(VkCommandBuffer cmd, CrumblingFrame cf, InstanceType<?> instanceType, IndirectDraw draw,
                              int objectSlot, SimpleMaterial.Builder crumblingMaterial, long crackView) {
        FrameSet fs = cf.fs();
        if (fs.meshVisualModelView == null) {
            return false;
        }
        var mesh = draw.mesh();
        int triCount = mesh.indexCount() / 3;
        if (triCount == 0) {
            return true;
        }
        VkMeshPipeline pipeline = meshVisualPipelines().crumblingPipeline(crumblingMaterial, instanceType, COLOR_FORMAT,
                DEPTH_FORMAT);
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());

        Material material = draw.material();
        writer.storage(1, cf.objectBuffer());
        writeLight(fs);
        writeAtlasTrio(cf.tm(), material.texture(), VkContext.sampler(MaterialSamplers.get(material)),
                cf.overlayView(), cf.loSampler(), cf.lightmapView());
        writer.sampler(13, crackView, cf.crackSampler())
              .uniform(9, fs.meshVisualModelView)
              .uniform(16, cf.projection())
              .uniform(17, cf.dynamicTransforms())
              .uniform(18, cf.fog())
              .uniform(19, cf.lights())
              .uniform(22, cf.renderOriginSlice());
        writer.flush(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long pc = stack.nmalloc(VkMeshVisualPipelines.PUSH_BYTES);
            MemoryUtil.memPutLong(pc, VkMeshVisualPipelines.deviceAddress(cf.vertexVk()));
            MemoryUtil.memPutLong(pc + 8L, VkMeshVisualPipelines.deviceAddress(cf.indexVk()));
            MemoryUtil.memPutLong(pc + 16L, 0L); // boundsAddr: unused by the crumbling variant
            MemoryUtil.memPutInt(pc + 24L, 0);   // baseDraw: unused
            MemoryUtil.memPutInt(pc + 28L, objectSlot);
            MemoryUtil.memPutInt(pc + 32L, mesh.firstIndex());
            MemoryUtil.memPutInt(pc + 36L, mesh.baseVertex());
            MemoryUtil.memPutInt(pc + 40L, triCount);
            MemoryUtil.memPutInt(pc + 44L, MaterialEncoder.packProperties(crumblingMaterial));
            VK10.nvkCmdPushConstants(cmd, pipeline.layout().pipelineLayout(),
                    EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT, 0, VkMeshVisualPipelines.PUSH_BYTES, pc);
        }
        EXTMeshShader.vkCmdDrawMeshTasksEXT(cmd, (triCount + 63) / 64, 1, 1);
        return true;
    }

    private record MeshVisualInputs(VkBuffer commands, long vertsAddr, long indicesAddr, long boundsAddr,
                                    VkBuffer modelViewUbo) {
    }
}
