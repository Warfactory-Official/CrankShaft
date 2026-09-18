package me.mlbv.meshlet.mesh.gl;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.iris.engine.GuestEngine;
import dev.engine_room.flywheel.iris.engine.GuestIndirectDrawManager;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LevelAccessor;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.KHRShaderSubgroup;
import org.lwjgl.opengl.NVMeshShader;

final class IrisMeshBackends {
    static final Backend MESH = SimpleBackend.builder()
                                             .engineFactory(MeshGuest::new)
                                             .priority(895)
                                             .gpuDriven(true)
                                             .supported(() -> GuestTerrainGate.MESH_ENABLED && !VkContext.isVulkanHost()
                                                     && GlCompat.SUPPORTS_TERRAIN_MESH && GL.getCapabilities().glMultiDrawMeshTasksIndirectCountNV != 0
                                                     && GL.getCapabilities().GL_KHR_shader_subgroup && GlCompat.SUBGROUP_SIZE == 32
                                                     && (GL11C.glGetInteger(
                                                     KHRShaderSubgroup.GL_SUBGROUP_SUPPORTED_FEATURES_KHR)
                                                     & KHRShaderSubgroup.GL_SUBGROUP_FEATURE_ARITHMETIC_BIT_KHR) != 0
                                                     && (GL11C.glGetInteger(
                                                     KHRShaderSubgroup.GL_SUBGROUP_SUPPORTED_STAGES_KHR)
                                                     & NVMeshShader.GL_TASK_SHADER_BIT_NV) != 0
                                                     && IndirectPrograms.allLoaded() && Iris.isPackInUseQuick())
                                             .register(
                                                     Identifier.fromNamespaceAndPath(Flywheel.ID, "iris_mesh_shader"));

    static void init() {
    }

    private static final class MeshGuest extends GuestEngine {
        private static @Nullable MeshGuest terrainOwner;
        private final @Nullable IrisTerrainRasterizer terrain;

        MeshGuest(LevelAccessor level) {
            super(level, new GuestIndirectDrawManager(IndirectPrograms.get()));
            // Auxiliary VisualizationLevels own visuals, not Sodium's main-world terrain arena.
            terrain = level == Minecraft.getInstance().level ? new IrisTerrainRasterizer() : null;
            if (terrain != null) {
                terrainOwner = this;
                TerrainDrawDispatcher.setMeshDrawStrategy(terrain::draw);
                GuestTerrainGate.meshActive = true;
            }
        }

        @Override
        public void delete() {
            if (terrainOwner == this) {
                terrainOwner = null;
                GuestTerrainGate.meshActive = false;
                TerrainDrawDispatcher.setMeshDrawStrategy(null);
            }
            if (terrain != null) terrain.delete();
            super.delete();
        }
    }
}
