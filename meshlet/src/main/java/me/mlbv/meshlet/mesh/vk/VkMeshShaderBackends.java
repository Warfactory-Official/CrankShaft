// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock

package me.mlbv.meshlet.mesh.vk;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.Backends;
#if MC >= 26.3
import dev.engine_room.flywheel.backend.compile.NativeOitWarmup;
import dev.engine_room.flywheel.backend.compile.NativeVkEngineWarmup;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.engine.indirect.VkNativeOitPass;
#endif
import dev.engine_room.flywheel.backend.compile.ProgramAvailability;
import dev.engine_room.flywheel.backend.compile.ShaderWarmup;
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.engine.indirect.VkMeshVisualDrawManager;
import dev.engine_room.flywheel.backend.engine.terrain.VkTerrainDrawManager;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.impl.compat.SodiumCompat;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.LevelAccessor;
import org.jspecify.annotations.Nullable;

public final class VkMeshShaderBackends {
    public static final Backend VK_MESH_SHADER = register();
    private static final int PRIORITY = 1900;

    private VkMeshShaderBackends() {
    }

    private static Backend register() {
        ShaderWarmup.registerVk(ProgramAvailability.Feature.MESH, VkMeshShaderBackends::warmUp);
        #if MC >= 26.3
        NativeVkEngineWarmup.registerTerrain(VkMeshShaderBackends::warmNativeTerrain);
        #endif
        return SimpleBackend.builder()
                            .engineFactory(VkMeshShaderBackends::createEngine)
                            .priority(PRIORITY)
                            .supported(VkMeshShaderBackends::isSupported)
                            .gpuDriven(true)
                            .register(Backends.VK_MESH_SHADER_ID);
    }

    private static void warmUp() {
        if (!isSupported()) {
            return;
        }
        VkMeshPipelines pipelines = new VkMeshPipelines();
        try {
            pipelines.warmUp();
        } finally {
            pipelines.destroy();
        }
    }

    #if MC >= 26.3
    private static void warmNativeTerrain(VkNativeOitPass pass, @Nullable OitInsertMode insert) {
        MeshEngine owner = MeshEngine.terrainOwner;
        if (owner != null) owner.pipelines.warmNative(pass, insert);
    }
    #endif

    private static boolean isSupported() {
        VkPrograms programs = VkPrograms.get();
        return VkContext.isVulkanHost()
                && !ShadersModHelper.isShaderPackInUse()
                && VkCaps.MESH_SHADER_NEGOTIATED
                && ProgramAvailability.allows(ProgramAvailability.Feature.MESH)
                && programs != null && programs.instanceRoute() == VkPrograms.InstanceRoute.INDIRECT
                && SodiumCompat.ACTIVE;
    }

    private static EngineImpl createEngine(LevelAccessor level) {
        return new MeshEngine(level);
    }

    private static final class MeshEngine extends EngineImpl {
        private static @Nullable MeshEngine terrainOwner;
        private final VkMeshPipelines pipelines = new VkMeshPipelines();
        private final VkPrimaryTerrainRasterizer rasterizer = new VkPrimaryTerrainRasterizer(pipelines);
        private final VkTranslucentTerrainRasterizer translucentRasterizer = new VkTranslucentTerrainRasterizer(
                pipelines);

        MeshEngine(LevelAccessor level) {
            super(level, new VkMeshVisualDrawManager(VkPrograms.get()), Backends.MAX_ORIGIN_DISTANCE);
            // Auxiliary VisualizationLevels own visuals, not Sodium's main-world terrain arena.
            if (level == Minecraft.getInstance().level) {
                terrainOwner = this;
                VkTerrainDrawManager.setMeshDrawStrategy(rasterizer);
                VkTerrainDrawManager.setTranslucentMeshDrawStrategy(translucentRasterizer);
                #if MC >= 26.3
                // The live owner's PSO variants must exist before its first native producer bind.
                NativeOitWarmup.configurationChanged();
                #endif
            }
        }

        @Override
        public void delete() {
            if (terrainOwner == this) {
                terrainOwner = null;
                VkTerrainDrawManager.setMeshDrawStrategy(null);
                VkTerrainDrawManager.setTranslucentMeshDrawStrategy(null);
            }
            rasterizer.destroy();
            translucentRasterizer.destroy();
            pipelines.destroy();
            super.delete();
        }
    }
}
