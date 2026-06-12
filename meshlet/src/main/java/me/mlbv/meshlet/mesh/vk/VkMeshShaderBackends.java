// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock

package me.mlbv.meshlet.mesh.vk;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.Backends;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.engine.indirect.VkMeshVisualDrawManager;
import dev.engine_room.flywheel.backend.compile.ShaderWarmup;
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.engine.terrain.VkTerrainDrawManager;
import dev.engine_room.flywheel.impl.compat.SodiumCompat;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;


import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LevelAccessor;

import org.jspecify.annotations.Nullable;

public final class VkMeshShaderBackends {
    private static final int PRIORITY = 1900;

    public static final Backend VK_MESH_SHADER = register();

    private static Backend register() {
        ShaderWarmup.register(VkMeshShaderBackends::warmUp);
        return SimpleBackend.builder()
                .engineFactory(VkMeshShaderBackends::createEngine)
                .priority(PRIORITY)
                .supported(VkMeshShaderBackends::isSupported)
                .gpuDriven(true)
                .register(Identifier.fromNamespaceAndPath(Flywheel.ID, "vk_mesh_shader"));
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

    private VkMeshShaderBackends() {
    }

    private static boolean isSupported() {
        return VkContext.isVulkanHost()
                && VkCaps.MESH_SHADER_NEGOTIATED
                && VkPrograms.allLoaded()
                && SodiumCompat.isSodiumActive();
    }

    private static EngineImpl createEngine(LevelAccessor level) {
        return new MeshEngine(level);
    }

    private static final class MeshEngine extends EngineImpl {
        private final VkMeshPipelines pipelines = new VkMeshPipelines();
        private final VkPrimaryTerrainRasterizer rasterizer = new VkPrimaryTerrainRasterizer(pipelines);
        private final VkTranslucentTerrainRasterizer translucentRasterizer = new VkTranslucentTerrainRasterizer(pipelines);
        @Nullable
        private final VkMeshGeometryArena arena = BackendConfig.INSTANCE.ownGeometry() ? new VkMeshGeometryArena(pipelines) : null;

        MeshEngine(LevelAccessor level) {
            super(level, new VkMeshVisualDrawManager(VkPrograms.get()), Backends.MAX_ORIGIN_DISTANCE);
            rasterizer.setArena(arena);
            translucentRasterizer.setArena(arena);
            VkTerrainDrawManager.setMeshDrawStrategy(rasterizer);
            VkTerrainDrawManager.setTranslucentMeshDrawStrategy(translucentRasterizer);
        }

        @Override
        public void delete() {
            VkTerrainDrawManager.setMeshDrawStrategy(null);
            VkTerrainDrawManager.setTranslucentMeshDrawStrategy(null);
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
