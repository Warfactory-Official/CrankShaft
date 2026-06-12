// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock (modifications: the GL_NV port + CrankShaft integration)
// Derivative work of Nvidium me.cortex.nvidium.renderers.PrimaryTerrainRasterizer (translucent variant).
package me.mlbv.meshlet.mesh.gl;

import java.nio.ByteBuffer;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainTranslucentMeshDrawStrategy;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

// Peer producer into CrankShaft's OIT chain (not back-to-front sorted). Decode-once: the emit-half builds the
// compacted section list (prepareCommands, outside any pass), then the FIRST draw of the frame runs
// translucent_gather.comp ONCE into a per-frame vertex cache and every OIT producer pass replays it. Both fade
// streams unify in the cache, so the strategy's fading=true calls are no-ops.
public final class GlTranslucentTerrainRasterizer implements TerrainTranslucentMeshDrawStrategy {
    private static final int BINDING_TERRAIN_SCENE_UBO = 7;
    private static final long TERRAIN_SCENE_UBO_BYTES = 64L;
    private static final int BINDING_HIZ_UBO = 8;
    private static final int BINDING_FOG = 9;
    private static final int BINDING_COMPACT_SECTIONS = 7;
    private static final int BINDING_GEOMETRY_POINTERS = 12;
    // NV_shader_buffer_store: makes the owned-geometry gather's SSBO writes visible to the mesh stage's bindless reads.
    private static final int GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV = 0x00000010;
    private static final int BINDING_COMPACT_GEOM = 13;
    private static final int BINDING_LIVE_MASK = 14;
    private static final int BINDING_CACHED_VERTS = 10;
    private static final int BINDING_QUAD_FADE = 11;
    private static final int BINDING_DRAW_INDIRECT = 15;
    private static final long CACHED_VERT_BYTES = 24L;

    private static final int COMPACT_SECTIONS_PER_REGION = 512;
    private static final int COMPACT_GEOM_PER_REGION = COMPACT_SECTIONS_PER_REGION * 2;

    private static final int UNIT_ATLAS = 0;
    private static final int UNIT_LIGHTMAP = 2;
    private static final int UNIT_DEPTH_RANGE = 3;
    private static final int UNIT_BLUE_NOISE = 4;
    private static final int UNIT_COEFF0 = 5;

    private static final int[] DRAW_BUFFERS_ONE = {GL30C.GL_COLOR_ATTACHMENT0};
    private static final int[] DRAW_BUFFERS_FOUR = {
            GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT0 + 1,
            GL30C.GL_COLOR_ATTACHMENT0 + 2, GL30C.GL_COLOR_ATTACHMENT0 + 3};

    private final GlMeshPipelines pipelines;

    private int compactSectionsBuffer = 0;
    private int compactSectionsCapacityRegions = 0;
    private int compactGeomBuffer = 0;

    private int sceneUbo = 0;
    private final ByteBuffer sceneUboScratch = MemoryUtil.memAlloc((int) TERRAIN_SCENE_UBO_BYTES);

    private final GlGeometryPtrBuffer geometryPtrs = new GlGeometryPtrBuffer();
    private final GlResidentAddressCache residentAddresses = new GlResidentAddressCache();
    private boolean gatheredThisFrame = false;

    private int cachedVertsBuffer = 0;
    private int quadFadeBuffer = 0;
    private int drawIndirectBuffer = 0;
    // Empty VAO for the vertex-pulling draws (core profile requires one bound even with no attributes).
    private int pullVao = 0;
    private long cachedQuadCapacity = 0;
    private final ByteBuffer indirectScratch = MemoryUtil.memAlloc(16);

    private int lightmapSamplerObj = 0;
    private int oitReadSamplerObj = 0;
    private int noiseSamplerObj = 0;

    @Nullable
    private GlMeshGeometryArena arena;

    public GlTranslucentTerrainRasterizer(GlMeshPipelines pipelines) {
        this.pipelines = pipelines;
    }

    public void setArena(@Nullable GlMeshGeometryArena arena) {
        this.arena = arena;
    }

    @Override
    public void prepareCommands(TerrainDrawDispatcher d) {
        int regionCount = d.translucentRegionBatch.count;
        if (regionCount <= 0) {
            return;
        }
        if (!pipelines.ensureTranslucentCompiled(TerrainAtlasFilter.linear())) {
            return;
        }

        ensureCompactSectionsCapacity(regionCount);
        if (arena != null) {
            arena.attach(d.registry);
            arena.tick();
            GlMeshUtil.gatherOwnedGeometry(d.translucentRegionBatch.regionIds, d.translucentRegionBatch.geometryBuffers,
                    regionCount, pipelines.gatherProgram(), arena);
        }
        gatheredThisFrame = false;

        uploadAndBindSceneUbo(d, regionCount);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_COMPACT_SECTIONS, compactSectionsBuffer);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_COMPACT_GEOM, compactGeomBuffer);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_LIVE_MASK, d.translucentLiveMaskBuffer.handle());
        GlStateTracker.useProgram(pipelines.translucentBuilderProgram());
        GL43C.glDispatchCompute(regionCount, 1, 1);
        GlStateTracker.useProgram(0);

        GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_COMMAND_BARRIER_BIT
                | GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
    }

    @Override
    public void draw(TerrainDrawDispatcher d, RenderPass pass, OitMode mode, OitFramebuffer framebuffer,
                     boolean fading, GpuTextureView lightmapView, GpuTextureView blueNoiseView,
                     GpuSampler clampLinear, GpuSampler oitSampler, GpuSampler noiseSampler) {
        if (fading) {
            return;
        }
        int regionCount = d.translucentRegionBatch.count;
        if (regionCount <= 0) {
            return;
        }
        if (!pipelines.ensureTranslucentCompiled(TerrainAtlasFilter.linear())) {
            return;
        }

        ensureSamplers();
        setupOitState(mode);

        // Atlas fetched here (not passed); must be the shared LINEAR+mipmap cache entry -- flw_sampleAtlas
        // requires the mip taps (a plain non-mip LINEAR sampler broke the crisp/NEAREST look).
        GpuTextureView atlasView = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        int atlasSamplerObj = ((GlSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true)).getId();
        GlMeshUtil.bindTexture(UNIT_ATLAS, atlasView, atlasSamplerObj);
        GlMeshUtil.bindTexture(UNIT_LIGHTMAP, lightmapView, lightmapSamplerObj);
        if (mode != OitMode.DEPTH_RANGE) {
            GlMeshUtil.bindTexture(UNIT_DEPTH_RANGE, framebuffer.depthBoundsView(), oitReadSamplerObj);
            GlMeshUtil.bindTexture(UNIT_BLUE_NOISE, blueNoiseView, noiseSamplerObj);
        }
        if (mode == OitMode.EVALUATE) {
            if (OitConfig.coefficientArray()) {
                framebuffer.bindCoefficientsArrayRaw();
            } else {
                for (int i = 0; i < 4; i++) {
                    GlMeshUtil.bindTexture(UNIT_COEFF0 + i, framebuffer.coefficientsView(i), oitReadSamplerObj);
                }
            }
        }

        // Re-bound defensively: engine binds can be clobbered between OIT modes/streams.
        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, BINDING_HIZ_UBO, d.translucentHizUniformBuffer.handle());
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        if (fog != null) {
            int fogHandle = GlMeshUtil.gpuBufferHandle(fog.buffer());
            if (fogHandle > 0) {
                GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, BINDING_FOG, fogHandle, fog.offset(), fog.length());
            }
        }

        gatherIfNeeded(d, regionCount);

        GlStateTracker.useProgram(pipelines.translucentProgram(mode));
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_CACHED_VERTS, cachedVertsBuffer);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_QUAD_FADE, quadFadeBuffer);
        GlBufferType.DRAW_INDIRECT_BUFFER.bind(drawIndirectBuffer);
        GlStateManager._glBindVertexArray(ensurePullVao());
        GL40C.glDrawArraysIndirect(GL11C.GL_TRIANGLES, 0L);
        GlStateTracker.useProgram(0);
        unbindSamplers();
    }

    // Decode-once: pointers + vertex-cache gather run at the FIRST translucent draw of the frame (after Sodium's
    // uploads), then every producer pass replays the cache; raw GL compute inside the open producer pass is legal.
    // Requires the caller to have bound HiZ UBO 8 + lightmap T2; scene UBO 7 + compact 7/13 feed ONLY this gather.
    private void gatherIfNeeded(TerrainDrawDispatcher d, int regionCount) {
        if (gatheredThisFrame) {
            return;
        }
        if (arena != null) {
            GlMeshUtil.uploadOwnedGeometryPointers(d.translucentRegionBatch.regionIds,
                    d.translucentRegionBatch.geometryBuffers, regionCount, geometryPtrs, residentAddresses, arena);
        } else {
            GlMeshUtil.uploadGeometryPointers(d.translucentRegionBatch.geometryBuffers, regionCount, geometryPtrs,
                    residentAddresses);
        }
        geometryPtrs.bindBase(BINDING_GEOMETRY_POINTERS);
        uploadAndBindSceneUbo(d, regionCount);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_COMPACT_SECTIONS, compactSectionsBuffer);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_COMPACT_GEOM, compactGeomBuffer);

        ensureCacheCapacity(d, regionCount);

        indirectScratch.clear();
        indirectScratch.putInt(0, 0).putInt(4, 1).putInt(8, 0).putInt(12, 0);
        indirectScratch.position(0).limit(16);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, drawIndirectBuffer);
        GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, indirectScratch);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        indirectScratch.clear();

        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_CACHED_VERTS, cachedVertsBuffer);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_QUAD_FADE, quadFadeBuffer);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_DRAW_INDIRECT, drawIndirectBuffer);
        GlStateTracker.useProgram(pipelines.translucentGatherProgram());
        GL43C.glDispatchCompute(regionCount, 1, 1);
        GlStateTracker.useProgram(0);
        GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_COMMAND_BARRIER_BIT);
        gatheredThisFrame = true;
    }

    private int ensurePullVao() {
        if (pullVao == 0) {
            pullVao = GL30C.glGenVertexArrays();
        }
        return pullVao;
    }

    private void ensureCacheCapacity(TerrainDrawDispatcher d, int regionCount) {
        long quads = 0;
        for (int i = 0; i < regionCount; i++) {
            quads += d.registry.translucentIndexCountSum(d.translucentRegionBatch.regionIds[i]) / 6;
        }
        quads = Math.max(quads, 1);
        if (cachedVertsBuffer != 0 && quads <= cachedQuadCapacity) {
            return;
        }
        long newCap = Math.max(quads, cachedQuadCapacity * 2);
        if (cachedVertsBuffer == 0) {
            cachedVertsBuffer = GL15C.glGenBuffers();
            quadFadeBuffer = GL15C.glGenBuffers();
        }
        if (drawIndirectBuffer == 0) {
            drawIndirectBuffer = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, drawIndirectBuffer);
            GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, 16L, GL15C.GL_DYNAMIC_COPY);
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, cachedVertsBuffer);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, newCap * 4L * CACHED_VERT_BYTES, GL15C.GL_DYNAMIC_COPY);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, quadFadeBuffer);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, newCap * Float.BYTES, GL15C.GL_DYNAMIC_COPY);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        cachedQuadCapacity = newCap;
    }

    @Override
    public void drawInsert(TerrainDrawDispatcher d, RenderPass pass, OitInsertMode mode, boolean fading,
                           GpuTextureView lightmapView, GpuSampler clampLinear) {
        if (fading) {
            return;
        }
        int regionCount = d.translucentRegionBatch.count;
        if (regionCount <= 0) {
            return;
        }
        boolean linear = TerrainAtlasFilter.linear();
        if (!pipelines.ensureTranslucentCompiled(linear)) {
            return;
        }
        if (!pipelines.ensureTranslucentMlabCompiled(mode, linear)) {
            return; // no wavelet fallback at this seam -- the frame is committed to insert (logged on failure)
        }

        ensureSamplers();
        setupInsertOitState();

        GpuTextureView atlasView = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        int atlasSamplerObj = ((GlSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true)).getId();
        GlMeshUtil.bindTexture(UNIT_ATLAS, atlasView, atlasSamplerObj);
        GlMeshUtil.bindTexture(UNIT_LIGHTMAP, lightmapView, lightmapSamplerObj);

        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, BINDING_HIZ_UBO, d.translucentHizUniformBuffer.handle());
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        if (fog != null) {
            int fogHandle = GlMeshUtil.gpuBufferHandle(fog.buffer());
            if (fogHandle > 0) {
                GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, BINDING_FOG, fogHandle, fog.offset(), fog.length());
            }
        }

        gatherIfNeeded(d, regionCount);

        GlStateTracker.useProgram(pipelines.translucentMlabProgram(mode));
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_CACHED_VERTS, cachedVertsBuffer);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING_QUAD_FADE, quadFadeBuffer);
        GlBufferType.DRAW_INDIRECT_BUFFER.bind(drawIndirectBuffer);
        GlStateManager._glBindVertexArray(ensurePullVao());
        GL40C.glDrawArraysIndirect(GL11C.GL_TRIANGLES, 0L);
        GlStateTracker.useProgram(0);

        GL33C.glBindSampler(UNIT_ATLAS, 0);
        GL33C.glBindSampler(UNIT_LIGHTMAP, 0);
    }

    // Insert has no color outputs (the frag writes the mlab sample SSBOs), so no MRT blend/draw-buffer table -- only
    // the reversed-Z depth discipline mlab's early_fragment_tests relies on.
    private static void setupInsertOitState() {
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_GEQUAL);
        GlStateManager._depthMask(false);
        GlStateManager._enableCull();
        GlStateManager._colorMask(ColorTargetState.WRITE_ALL);
        GlStateManager._disableBlend(0);
        GL20C.glDrawBuffers(DRAW_BUFFERS_ONE);
    }

    private static void setupOitState(OitMode mode) {
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_GEQUAL);
        GlStateManager._depthMask(false);
        GlStateManager._enableCull();
        GlStateManager._colorMask(ColorTargetState.WRITE_ALL);

        GlStateManager._enableBlend(0);
        GlStateManager._blendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE);
        int eq = (mode == OitMode.DEPTH_RANGE) ? GL14C.GL_MAX : GL14C.GL_FUNC_ADD;
        GlStateManager._blendEquationSeparate(eq, eq);

        // _enableBlend(0) already enables the GLOBAL GL_BLEND over all draw buffers; the per-index
        // _enableBlend(1/2/3) only flips BLEND[i] cache bits over that same global flag -- dropped.
        if (mode == OitMode.GENERATE_COEFFICIENTS) {
            GL20C.glDrawBuffers(DRAW_BUFFERS_FOUR);
        } else {
            GL20C.glDrawBuffers(DRAW_BUFFERS_ONE);
        }
    }

    private void unbindSamplers() {
        GL33C.glBindSampler(UNIT_ATLAS, 0);
        GL33C.glBindSampler(UNIT_LIGHTMAP, 0);
        GL33C.glBindSampler(UNIT_DEPTH_RANGE, 0);
        GL33C.glBindSampler(UNIT_BLUE_NOISE, 0);
        for (int i = 0; i < 4; i++) {
            GL33C.glBindSampler(UNIT_COEFF0 + i, 0);
        }
    }

    private void ensureSamplers() {
        if (lightmapSamplerObj == 0) {
            lightmapSamplerObj = GL33C.glGenSamplers();
            GL33C.glSamplerParameteri(lightmapSamplerObj, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL33C.glSamplerParameteri(lightmapSamplerObj, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL33C.glSamplerParameteri(lightmapSamplerObj, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL33C.glSamplerParameteri(lightmapSamplerObj, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        }
        if (oitReadSamplerObj == 0) {
            oitReadSamplerObj = GL33C.glGenSamplers();
            GL33C.glSamplerParameteri(oitReadSamplerObj, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL33C.glSamplerParameteri(oitReadSamplerObj, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL33C.glSamplerParameteri(oitReadSamplerObj, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL33C.glSamplerParameteri(oitReadSamplerObj, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        }
        if (noiseSamplerObj == 0) {
            noiseSamplerObj = GL33C.glGenSamplers();
            GL33C.glSamplerParameteri(noiseSamplerObj, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL33C.glSamplerParameteri(noiseSamplerObj, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL33C.glSamplerParameteri(noiseSamplerObj, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
            GL33C.glSamplerParameteri(noiseSamplerObj, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
        }
    }

    private void uploadAndBindSceneUbo(TerrainDrawDispatcher d, int regionCount) {
        if (sceneUbo == 0) {
            sceneUbo = GL15C.glGenBuffers();
        }
        ByteBuffer s = sceneUboScratch;
        s.clear();
        s.putLong(0, d.translucentRegionInputBuffer.deviceAddress());
        s.putLong(8, d.registry.translucentSectionDataAddress());
        s.putLong(16, d.translucentRegionVisBuffer.deviceAddress());
        s.putLong(24, d.registry.translucentVisAddress());
        s.putLong(32, d.translucentCommandBuffer.deviceAddress());
        s.putLong(40, d.translucentCommandCount.deviceAddress());
        s.putLong(48, 0L);
        s.putInt(56, regionCount);
        s.position(0).limit((int) TERRAIN_SCENE_UBO_BYTES);
        GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, sceneUbo);
        GL15C.glBufferData(GL31C.GL_UNIFORM_BUFFER, TERRAIN_SCENE_UBO_BYTES, GL15C.GL_STREAM_DRAW);
        GL15C.glBufferSubData(GL31C.GL_UNIFORM_BUFFER, 0L, s);
        GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);
        s.clear();
        GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, BINDING_TERRAIN_SCENE_UBO, sceneUbo, 0L, TERRAIN_SCENE_UBO_BYTES);
    }

    private void ensureCompactSectionsCapacity(int regionCount) {
        if (compactSectionsBuffer != 0 && regionCount <= compactSectionsCapacityRegions) {
            return;
        }
        int newCap = Math.max(regionCount, compactSectionsCapacityRegions * 2);
        if (compactSectionsBuffer == 0) {
            compactSectionsBuffer = GL15C.glGenBuffers();
        }
        if (compactGeomBuffer == 0) {
            compactGeomBuffer = GL15C.glGenBuffers();
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, compactSectionsBuffer);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER,
                (long) newCap * COMPACT_SECTIONS_PER_REGION * Integer.BYTES, GL15C.GL_STREAM_DRAW);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, compactGeomBuffer);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER,
                (long) newCap * COMPACT_GEOM_PER_REGION * Integer.BYTES, GL15C.GL_STREAM_DRAW);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        compactSectionsCapacityRegions = newCap;
    }

    public void destroy() {
        residentAddresses.clear();
        if (compactSectionsBuffer != 0) {
            GL15C.glDeleteBuffers(compactSectionsBuffer);
            compactSectionsBuffer = 0;
            compactSectionsCapacityRegions = 0;
        }
        if (compactGeomBuffer != 0) {
            GL15C.glDeleteBuffers(compactGeomBuffer);
            compactGeomBuffer = 0;
        }
        geometryPtrs.destroy();
        if (cachedVertsBuffer != 0) {
            GL15C.glDeleteBuffers(cachedVertsBuffer);
            cachedVertsBuffer = 0;
        }
        if (quadFadeBuffer != 0) {
            GL15C.glDeleteBuffers(quadFadeBuffer);
            quadFadeBuffer = 0;
        }
        if (drawIndirectBuffer != 0) {
            GL15C.glDeleteBuffers(drawIndirectBuffer);
            drawIndirectBuffer = 0;
        }
        cachedQuadCapacity = 0;
        if (pullVao != 0) {
            GL30C.glDeleteVertexArrays(pullVao);
            pullVao = 0;
        }
        if (sceneUbo != 0) {
            GL15C.glDeleteBuffers(sceneUbo);
            sceneUbo = 0;
        }
        MemoryUtil.memFree(sceneUboScratch);
        MemoryUtil.memFree(indirectScratch);
        if (lightmapSamplerObj != 0) {
            GL33C.glDeleteSamplers(lightmapSamplerObj);
            lightmapSamplerObj = 0;
        }
        if (oitReadSamplerObj != 0) {
            GL33C.glDeleteSamplers(oitReadSamplerObj);
            oitReadSamplerObj = 0;
        }
        if (noiseSamplerObj != 0) {
            GL33C.glDeleteSamplers(noiseSamplerObj);
            noiseSamplerObj = 0;
        }
    }

}
