// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock
package me.mlbv.meshlet.mesh.gl;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.Backends;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
#if MC >= 26.3
import dev.engine_room.flywheel.backend.compile.NativeOitWarmup;
#endif
import dev.engine_room.flywheel.backend.compile.ProgramAvailability;
import dev.engine_room.flywheel.backend.compile.ShaderCompilationMode;
import dev.engine_room.flywheel.backend.compile.ShaderWarmup;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.engine.indirect.MeshVisualDrawManager;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.impl.compat.CompatMod;
import dev.engine_room.flywheel.impl.compat.SodiumCompat;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.LevelAccessor;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

public final class MeshShaderBackends {
    public static final Backend GL_MESH_SHADER = register();
    private static final int PRIORITY = 900;

    private MeshShaderBackends() {
    }

    private static Backend register() {
        if (CompatMod.IRIS.isLoaded) {
            IrisMeshBackends.init();
        }
        ShaderWarmup.register(ProgramAvailability.Feature.MESH, MeshShaderBackends::warmUp);
        #if MC >= 26.3
        NativeOitWarmup.registerDeclarations(MeshShaderBackends::warmNativeTerrain);
        #endif
        return SimpleBackend.builder()
                            .engineFactory(MeshShaderBackends::createEngine)
                            .priority(PRIORITY)
                            .supported(MeshShaderBackends::isSupported)
                            .gpuDriven(true)
                            .register(Backends.GL_MESH_SHADER_ID);
    }

    private static void warmUp() {
        if (BackendManager.currentBackend() != GL_MESH_SHADER || VkContext.isVulkanHost()
                || BackendConfig.INSTANCE.terrainMode() == TerrainMode.OFF
                || BackendConfig.INSTANCE.shaderCompilation() == ShaderCompilationMode.ON_DEMAND
                || !isSupported()) {
            return;
        }
        GlMeshPipelines pipelines = new GlMeshPipelines();
        try {
            pipelines.warmUp();
        } finally {
            pipelines.destroy();
        }
    }

    #if MC >= 26.3
    private static void warmNativeTerrain() {
        MeshEngine owner = MeshEngine.terrainOwner;
        if (owner != null && BackendManager.currentBackend() == GL_MESH_SHADER
                && BackendConfig.INSTANCE.terrainMode().compositesTranslucent()
                && BackendConfig.INSTANCE.shaderCompilation() != ShaderCompilationMode.ON_DEMAND) {
            owner.pipelines.warmTranslucent();
        }
    }
    #endif

    private static boolean isSupported() {
        boolean meshSupport = GlCompat.SUPPORTS_TERRAIN_MESH;
        if (!meshSupport || !ProgramAvailability.allows(ProgramAvailability.Feature.MESH)) {
            return false;
        }
        long fnPtr = GL.getCapabilities().glMultiDrawMeshTasksIndirectNV;
        boolean indirectLoaded = IndirectPrograms.allLoaded();
        // Sodium is mandatory: the terrain tier consumes Sodium's live geometry arena and the rasterizers reference
        // its classes -- without it createEngine() would NoClassDefFoundError, so never select this backend.
        boolean sodium = SodiumCompat.ACTIVE;
        FlwBackend.LOGGER.info(
                "[gl_mesh] isSupported: SUPPORTS_TERRAIN_MESH={} fnPtr={} allLoaded={} sodium={} | IndirectPrograms.CL={} id={}",
                meshSupport, fnPtr, indirectLoaded, sodium, IndirectPrograms.class.getClassLoader(),
                Integer.toHexString(System.identityHashCode(IndirectPrograms.class)));
        return meshSupport && fnPtr != MemoryUtil.NULL && indirectLoaded && sodium
                && !ShadersModHelper.isShaderPackInUse();
    }

    private static EngineImpl createEngine(LevelAccessor level) {
        return new MeshEngine(level);
    }

    private static final class MeshEngine extends EngineImpl {
        private static @Nullable MeshEngine terrainOwner;
        private final GlMeshPipelines pipelines = new GlMeshPipelines();
        private final GlPrimaryTerrainRasterizer rasterizer = new GlPrimaryTerrainRasterizer(pipelines);
        private final GlTranslucentTerrainRasterizer translucentRasterizer = new GlTranslucentTerrainRasterizer(
                pipelines);

        MeshEngine(LevelAccessor level) {
            super(level, new MeshVisualDrawManager(IndirectPrograms.get()), Backends.MAX_ORIGIN_DISTANCE);
            // Auxiliary VisualizationLevels own visuals, not Sodium's main-world terrain arena.
            if (level == Minecraft.getInstance().level) {
                terrainOwner = this;
                TerrainDrawDispatcher.setMeshDrawStrategy(rasterizer::draw);
                TerrainDrawDispatcher.setTranslucentMeshDrawStrategy(translucentRasterizer);
                #if MC >= 26.3
                NativeOitWarmup.configurationChanged();
                #endif
            }
        }

        @Override
        public void delete() {
            if (terrainOwner == this) {
                terrainOwner = null;
                TerrainDrawDispatcher.setMeshDrawStrategy(null);
                TerrainDrawDispatcher.setTranslucentMeshDrawStrategy(null);
            }
            rasterizer.destroy();
            translucentRasterizer.destroy();
            pipelines.destroy();
            super.delete();
        }
    }
}
