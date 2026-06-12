// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock
package me.mlbv.meshlet.mesh.gl;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.Backends;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compile.ShaderWarmup;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.engine.indirect.MeshVisualDrawManager;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.impl.compat.SodiumCompat;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import dev.engine_room.flywheel.backend.FlwBackend;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LevelAccessor;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

// Self-registers GL_MESH_SHADER via its static field initializer; BackendManagerImpl.init() force-loads it by FQN
// within the registry-freeze window (the :meshlet->:common dependency is one-way). Visuals reuse the INDIRECT engine.
public final class MeshShaderBackends {
    private static final int PRIORITY = 900;

    public static final Backend GL_MESH_SHADER = register();

    private static Backend register() {
        ShaderWarmup.register(MeshShaderBackends::warmUp);
        return SimpleBackend.builder()
                .engineFactory(MeshShaderBackends::createEngine)
                .priority(PRIORITY)
                .supported(MeshShaderBackends::isSupported)
                .gpuDriven(true)
                .register(Identifier.fromNamespaceAndPath(Flywheel.ID, "gl_mesh_shader"));
    }

    private static void warmUp() {
        if (VkContext.isVulkanHost() || !isSupported()) {
            return;
        }
        GlMeshPipelines pipelines = new GlMeshPipelines();
        try {
            pipelines.warmUp();
        } finally {
            pipelines.destroy();
        }
    }

    private MeshShaderBackends() {
    }

    private static boolean isSupported() {
        boolean meshSupport = GlCompat.SUPPORTS_TERRAIN_MESH;
        if (!meshSupport) {
            return false;
        }
        long fnPtr = GL.getCapabilities().glMultiDrawMeshTasksIndirectNV;
        boolean indirectLoaded = IndirectPrograms.allLoaded();
        // Sodium is mandatory: the terrain tier consumes Sodium's live geometry arena and the rasterizers reference
        // its classes -- without it createEngine() would NoClassDefFoundError, so never select this backend.
        boolean sodium = SodiumCompat.isSodiumActive();
        FlwBackend.LOGGER.info(
                "[gl_mesh] isSupported: SUPPORTS_TERRAIN_MESH={} fnPtr={} allLoaded={} sodium={} | IndirectPrograms.CL={} id={}",
                meshSupport, fnPtr, indirectLoaded, sodium, IndirectPrograms.class.getClassLoader(),
                Integer.toHexString(System.identityHashCode(IndirectPrograms.class)));
        return meshSupport && fnPtr != MemoryUtil.NULL && indirectLoaded && sodium;
    }

    private static EngineImpl createEngine(LevelAccessor level) {
        return new MeshEngine(level);
    }

    private static final class MeshEngine extends EngineImpl {
        private final GlMeshPipelines pipelines = new GlMeshPipelines();
        private final GlPrimaryTerrainRasterizer rasterizer = new GlPrimaryTerrainRasterizer(pipelines);
        private final GlTranslucentTerrainRasterizer translucentRasterizer = new GlTranslucentTerrainRasterizer(pipelines);
        @Nullable
        private final GlMeshGeometryArena arena = BackendConfig.INSTANCE.ownGeometry() ? new GlMeshGeometryArena(pipelines) : null;

        MeshEngine(LevelAccessor level) {
            super(level, new MeshVisualDrawManager(IndirectPrograms.get()), Backends.MAX_ORIGIN_DISTANCE);
            rasterizer.setArena(arena);
            translucentRasterizer.setArena(arena);
            TerrainDrawDispatcher.setMeshDrawStrategy(rasterizer::draw);
            TerrainDrawDispatcher.setTranslucentMeshDrawStrategy(translucentRasterizer);
        }

        @Override
        public void delete() {
            TerrainDrawDispatcher.setMeshDrawStrategy(null);
            TerrainDrawDispatcher.setTranslucentMeshDrawStrategy(null);
            rasterizer.destroy();
            translucentRasterizer.destroy();
            if (arena != null) {
                arena.destroy();
            }
            pipelines.destroy();
            super.delete();
        }
    }
}
