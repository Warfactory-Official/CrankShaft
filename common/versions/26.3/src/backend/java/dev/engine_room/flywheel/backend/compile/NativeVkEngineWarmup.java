package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.Backends;
import dev.engine_room.flywheel.backend.engine.OitTransparency;
import dev.engine_room.flywheel.backend.engine.indirect.VkNativeOitPass;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import dev.engine_room.flywheel.lib.util.ShaderWarmupRegistry;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Cold preparation of actual engine producer PSOs for the retained native render-pass specifications. */
public final class NativeVkEngineWarmup {
    private static final List<TerrainHook> TERRAIN_HOOKS = new ArrayList<>();

    private NativeVkEngineWarmup() {
    }

    /** Module initialization only; hooks warm their live terrain owner without creating visualization levels. */
    public static void registerTerrain(TerrainHook hook) {
        TERRAIN_HOOKS.add(hook);
    }

    @FunctionalInterface
    public interface TerrainHook {
        void warm(VkNativeOitPass pass, @Nullable OitInsertMode insert);
    }

    /** One registry snapshot per loading/configuration operation; no draw path consumes this object. */
    static Snapshot snapshot() {
        RenderSystem.assertOnRenderThread();
        return new Snapshot();
    }

    static final class Snapshot {
        private final VkPrograms programs = VkPrograms.get();
        private final List<Material> materials = ShaderWarmup.nativeWarmMaterials();
        private final List<InstanceType<?>> types = ShaderWarmupRegistry.types();
        private final LightSmoothness smoothness = BackendConfig.INSTANCE.lightSmoothness();
        private final boolean mesh =
                Backends.VK_MESH_SHADER_ID.equals(Backend.REGISTRY.getIdOrThrow(BackendManager.currentBackend()));
        private final boolean sodium = NativeVkEngineWarmup.class.getClassLoader()
                .getResource("net/caffeinemc/mods/sodium/client/SodiumClientMod.class") != null;

        void warm(VkNativeOitPass pass, @Nullable OitInsertMode insert) {
            for (int i = 0; i < pass.phaseCount(); i++) {
                var phase = pass.phase(i);
                var compatibility = pass.engineCompatibility(i);
                switch (phase) {
                    case DEPTH_MAIN, COEFFICIENT_MAIN, EVALUATE_MAIN -> {
                        if (insert == null) instances(compatibility, phase.mode(), null, false, true);
                    }
                    case EMISSION_MAIN -> instances(compatibility, OitMode.EVALUATE, null, true, false);
                    case INSERT_MAIN -> instances(compatibility, OitMode.EVALUATE, insert, false, false);
                    case DEPTH_TERRAIN -> terrain(compatibility, OitMode.DEPTH_RANGE, null);
                    case COEFFICIENT_TERRAIN, EVALUATE_TERRAIN -> {
                        if (insert == null) terrain(compatibility, phase.mode(), null);
                    }
                    case INSERT_TERRAIN -> terrain(compatibility, OitMode.EVALUATE, insert);
                    default -> {
                    }
                }
            }
            if (sodium && mesh) {
                for (TerrainHook hook : TERRAIN_HOOKS) hook.warm(pass, insert);
            }
        }

        private void instances(VkGraphicsPipeline.Subpass compatibility, OitMode mode,
                               @Nullable OitInsertMode insert, boolean additive, boolean folded) {
            for (Material material : materials) {
                if (!OitTransparency.orderIndependent(material)
                        || insert == null && OitTransparency.additive(material) != additive) continue;
                for (boolean embedded : new boolean[]{false, true}) {
                    if (mesh) {
                        for (InstanceType<?> type : types) {
                            var pipeline = insert == null
                                    ? programs.meshVisual().oitPipeline(type, material, embedded, mode,
                                            programs.depthFormat(), folded)
                                    : programs.meshVisual().mlabPipeline(type, material, embedded, insert,
                                            programs.depthFormat());
                            pipeline.subpassHandle(compatibility);
                        }
                    } else {
                        var pipeline = insert == null
                                ? programs.uber().oitProducerPipeline(material, embedded, smoothness, mode, folded)
                                : programs.uber().mlabProducerPipeline(insert, material, embedded, smoothness);
                        pipeline.subpassHandle(compatibility);
                    }
                }
            }
        }

        private void terrain(VkGraphicsPipeline.Subpass compatibility, OitMode mode,
                             @Nullable OitInsertMode insert) {
            if (!sodium || mesh && programs.usesGpuTranslucentTerrain()) return;
            boolean gpu = programs.usesGpuTranslucentTerrain();
            var terrain = programs.terrain();
            var pipeline = insert == null
                    ? gpu ? terrain.translucentProducerPipeline(mode, true)
                            : terrain.classicTranslucentProducerPipeline(mode, true)
                    : gpu ? terrain.translucentMlabPipeline(insert) : terrain.classicTranslucentMlabPipeline(insert);
            pipeline.subpassHandle(compatibility);
        }
    }
}
