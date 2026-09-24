// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock (modifications: the GL_NV port + CrankShaft integration)
// Derivative work of Nvidium me.cortex.nvidium.renderers.PrimaryTerrainRasterizer.
package me.mlbv.meshlet.mesh.gl;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;

// Opaque glMultiDrawMeshTasksIndirectNV draw, registered via TerrainDrawDispatcher.setMeshDrawStrategy. CrankShaft's cull/HiZ/section-test compute tail has ALREADY run and bound the resident SSBOs at their fixed bindings (0-6, 11), the HiZ UBO (8), and the depth pyramid at T10. Single multidraw (Nvidium's scheme): the emit-half encodes firstTask = slot&lt;&lt;8 per region so the task recovers its region as gl_WorkGroupID.x&gt;&gt;8 and its in-region index as &amp;0xFF -- no per-region GL state change. Reversed-Z: the HiZ pyramid (T10) is MIN-reduced and the cull uses reversed-Z; depth state is owned by the host render pass.
public final class GlPrimaryTerrainRasterizer {
    private static final int BINDING_TERRAIN_SCENE_UBO = 7;
    private static final long TERRAIN_SCENE_UBO_BYTES = 64L;
    // Engine-safe UBO range 8-12 (above Mojang's managed terrain UBOs), alongside HiZ (8).
    private static final int BINDING_FOG = 9;
    private static final int BINDING_COMPACT_SECTIONS = 7;
    private static final int BINDING_GEOMETRY_POINTERS = 12;

    private static final int MESH_TASK_COMMAND_STRIDE = 8;

    private static final int GL_DRAW_INDIRECT_UNIFIED_NV = 0x8F40;
    private static final int GL_DRAW_INDIRECT_ADDRESS_NV = 0x8F41;

    private static final int COMPACT_SECTIONS_PER_REGION = 256;

    private static final int UNIT_ATLAS = 0;
    private static final int UNIT_LIGHTMAP = 2;

    private final GlMeshPipelines pipelines;
    private int compactSectionsBuffer = 0;
    private int compactSectionsCapacityRegions = 0;
    private final GlGeometryPtrBuffer geometryPtrs = new GlGeometryPtrBuffer();
    private final GlResidentAddressCache residentAddresses = new GlResidentAddressCache();

    private int sceneUbo = 0;
    private final ByteBuffer sceneUboScratch = MemoryUtil.memAlloc((int) TERRAIN_SCENE_UBO_BYTES);

    // Engine-owned sampler objects: without these the unit inherits the prior pass's sampler -- a depth-compare
    // sampler turns colour terrain black.
    private int lightmapSamplerObj = 0;

    public GlPrimaryTerrainRasterizer(GlMeshPipelines pipelines) {
        this.pipelines = pipelines;
    }

    // Read everything synchronously off the dispatcher's public fields -- do not cache the instance (its fields mutate per pass).
    public void draw(TerrainDrawDispatcher d, int passIndex) {
        int regionCount = d.boundBatch.count;
        if (regionCount <= 0) {
            return;
        }
        int configKey = MeshFeatureConfig.currentKey();
        if (!pipelines.ensureCompiled(configKey)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return;
        }

        ensureCompactSectionsCapacity(regionCount);

        runEmitHalf(d, passIndex, regionCount);

        GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_COMMAND_BARRIER_BIT);

        GpuTextureView atlasView = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        GpuTextureView lightmapView = mc.gameRenderer.lightmap();

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(() -> "meshlet:terrain/gl_opaque",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            ensureSamplers();
            GlMeshUtil.bindTexture(UNIT_ATLAS, atlasView, ((GlSampler) TerrainAtlasFilter.sampler()).getId());
            GlMeshUtil.bindTexture(UNIT_LIGHTMAP, lightmapView, lightmapSamplerObj);
            setupOpaqueState();
            drawRegions(d, passIndex, regionCount);
            GL33C.glBindSampler(UNIT_ATLAS, 0);
            GL33C.glBindSampler(UNIT_LIGHTMAP, 0);
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        }
    }

    // An earlier pass may leave a partial color mask.
    private static void setupOpaqueState() {
        GlStateManager._colorMask(ColorTargetState.WRITE_ALL);
        GlStateManager._disableBlend(0);
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_GEQUAL);
        GlStateManager._depthMask(true);
        GlStateManager._enableCull();
    }

    private void ensureSamplers() {
        if (lightmapSamplerObj == 0) {
            lightmapSamplerObj = GL33C.glGenSamplers();
            GL33C.glSamplerParameteri(lightmapSamplerObj, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL33C.glSamplerParameteri(lightmapSamplerObj, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        }
    }

    private void runEmitHalf(TerrainDrawDispatcher d, int passIndex, int regionCount) {
        // compactSections is shared by both opaque passes: barrier against the prior pass's draw reads before we
        // overwrite -- a write-after-read hazard the GL pipeline would otherwise overlap.
        GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);

        uploadAndBindSceneUbo(d, passIndex, regionCount);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_COMPACT_SECTIONS, compactSectionsBuffer);

        GlStateTracker.useProgram(pipelines.commandBuilderProgram());
        GL43C.glDispatchCompute(regionCount, 1, 1);
    }

    private void uploadAndBindSceneUbo(TerrainDrawDispatcher d, int passIndex, int regionCount) {
        if (sceneUbo == 0) {
            sceneUbo = GL15C.glGenBuffers();
        }
        ByteBuffer s = sceneUboScratch;
        s.clear();
        s.putLong(0, d.regionInputBuffers[passIndex].deviceAddress());
        s.putLong(8, d.registry.sectionDataAddress(passIndex));
        s.putLong(16, d.regionVisBuffer.deviceAddress());
        s.putLong(24, d.registry.sectionVisAddress(passIndex));
        s.putLong(32, d.commandBuffers[passIndex].deviceAddress());
        s.putLong(40, d.regionCommandCounts[passIndex].deviceAddress());
        s.putLong(48, d.registry.translucentVisAddress());
        s.putInt(56, regionCount);
        s.putInt(60, 1 << (d.boundPhase - 1));
        s.position(0).limit((int) TERRAIN_SCENE_UBO_BYTES);
        GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, sceneUbo);
        GL15C.glBufferData(GL31C.GL_UNIFORM_BUFFER, TERRAIN_SCENE_UBO_BYTES, GL15C.GL_STREAM_DRAW);
        GL15C.glBufferSubData(GL31C.GL_UNIFORM_BUFFER, 0L, s);
        GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);
        s.clear();
        GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, BINDING_TERRAIN_SCENE_UBO, sceneUbo, 0L, TERRAIN_SCENE_UBO_BYTES);
    }

    private void drawRegions(TerrainDrawDispatcher d, int passIndex, int regionCount) {
        // pass 0 = solid (early-Z, no discard), pass 1 = cutout (runtime cutoffId discard, late-Z) -- mirrors VK.
        int terrainProgram = pipelines.terrainProgram(passIndex != 0);
        GlStateTracker.useProgram(terrainProgram);

        GpuBufferSlice fog = RenderSystem.getShaderFog();
        if (fog != null) {
            int fogHandle = GlMeshUtil.gpuBufferHandle(fog.buffer());
            if (fogHandle > 0) {
                GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, BINDING_FOG,
                        fogHandle, fog.offset(), fog.length());
            }
        }

        GlMeshUtil.uploadGeometryPointers(d.boundBatch.geometryBuffers, regionCount, geometryPtrs, residentAddresses);
        geometryPtrs.bindBase(BINDING_GEOMETRY_POINTERS);

        GL11.glEnableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        NVVertexBufferUnifiedMemory.glBufferAddressRangeNV(GL_DRAW_INDIRECT_ADDRESS_NV, 0,
                d.commandBuffers[passIndex].deviceAddress(), (long) regionCount * MESH_TASK_COMMAND_STRIDE);

        NVMeshShader.glMultiDrawMeshTasksIndirectNV(0L, regionCount, MESH_TASK_COMMAND_STRIDE);

        GL11.glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        GlStateTracker.useProgram(0);
    }

    private void ensureCompactSectionsCapacity(int regionCount) {
        if (compactSectionsBuffer != 0 && regionCount <= compactSectionsCapacityRegions) {
            return;
        }
        int newCap = Math.max(regionCount, compactSectionsCapacityRegions * 2);
        if (compactSectionsBuffer == 0) {
            compactSectionsBuffer = GL15C.glGenBuffers();
        }
        long bytes = (long) newCap * COMPACT_SECTIONS_PER_REGION * Integer.BYTES;
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, compactSectionsBuffer);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, bytes, GL15C.GL_STREAM_DRAW);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        compactSectionsCapacityRegions = newCap;
    }

    public void destroy() {
        if (compactSectionsBuffer != 0) {
            GL15C.glDeleteBuffers(compactSectionsBuffer);
            compactSectionsBuffer = 0;
            compactSectionsCapacityRegions = 0;
        }
        geometryPtrs.destroy();
        if (sceneUbo != 0) {
            GL15C.glDeleteBuffers(sceneUbo);
            sceneUbo = 0;
        }
        MemoryUtil.memFree(sceneUboScratch);
        residentAddresses.clear();
        if (lightmapSamplerObj != 0) {
            GL33C.glDeleteSamplers(lightmapSamplerObj);
            lightmapSamplerObj = 0;
        }
    }
}
