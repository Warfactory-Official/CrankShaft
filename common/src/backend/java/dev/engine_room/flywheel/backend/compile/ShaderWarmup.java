package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.CutoutShader;
import dev.engine_room.flywheel.api.material.FogShader;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.MaterialShaderIndices;
import dev.engine_room.flywheel.backend.engine.BerFamily;
import dev.engine_room.flywheel.backend.engine.CrumblingPipelines;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectPipeline;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.engine.indirect.MeshVisualDrawManager;
import dev.engine_room.flywheel.backend.engine.indirect.OitPipelines;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainPipelines;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.FogShaders;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import org.lwjgl.vulkan.VK12;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Precompiles the draw-pipeline set at resource reload, so world-load frames never hit driver shader compiles.
 * MUST run after vanilla's ShaderManager reload (its apply() clears the device pipeline cache).
 */
public final class ShaderWarmup {
    // Registered by modules :common cannot reference (the :meshlet tiers); run after the common warms.
    private static final List<Runnable> HOOKS = new ArrayList<>();

    /**
     * Seeded into {@link InstanceTypeIds} so the uber programs compile ONCE at full coverage (ids otherwise
     * grow on first sight, each growth keying a fresh uber compile).
     */
    private static final List<InstanceType<?>> WARM_TYPES = List.of(InstanceTypes.TRANSFORMED, InstanceTypes.POSED,
            InstanceTypes.ORIENTED, InstanceTypes.CLIP_TRANSFORMED, InstanceTypes.UV_TRANSFORMED,
            InstanceTypes.BILLBOARD, InstanceTypes.GLYPH, InstanceTypes.LEASH, InstanceTypes.SHADOW);

    private static final List<InstanceType<?>> STANDARD_TYPES = List.of(InstanceTypes.TRANSFORMED,
            InstanceTypes.POSED, InstanceTypes.ORIENTED);

    /**
     * The mesh-visual warm domain: the standard types + CLIP_TRANSFORMED, whose _FLW_MV_CLIP interface variant
     * is downstream API surface (warming fail-fast-validates the clip routing every reload).
     */
    private static final List<InstanceType<?>> MESH_VISUAL_TYPES = List.of(InstanceTypes.TRANSFORMED,
            InstanceTypes.POSED, InstanceTypes.ORIENTED, InstanceTypes.CLIP_TRANSFORMED);

    private ShaderWarmup() {
    }

    public static void register(Runnable hook) {
        HOOKS.add(hook);
    }

    static void warm() {
        long start = System.nanoTime();
        for (InstanceType<?> type : WARM_TYPES) {
            InstanceTypeIds.id(type);
        }
        for (CutoutShader cutout : libStatics(CutoutShaders.class, CutoutShader.class)) {
            MaterialShaderIndices.cutoutIndex(cutout);
        }
        for (FogShader fog : libStatics(FogShaders.class, FogShader.class)) {
            MaterialShaderIndices.fogIndex(fog);
        }
        if (VkContext.isVulkanHost()) {
            run("vk warm set", ShaderWarmup::warmVk);
        } else if (IndirectPrograms.allLoaded() && !ShadersModHelper.isShaderPackInUse()) {
            run("gl warm set", ShaderWarmup::warmGl);
        }
        for (Runnable hook : HOOKS) {
            run(hook.toString(), hook);
        }
        FlwPrograms.LOGGER.info("pipeline warm-up took {}ms", (System.nanoTime() - start) / 1_000_000);
    }

    private static void run(String what, Runnable warm) {
        try {
            warm.run();
        } catch (Throwable t) {
            FlwPrograms.LOGGER.error("pipeline warm-up failed: {}", what, t);
        }
    }

    private static List<Material> warmMaterials() {
        return libStatics(Materials.class, Material.class);
    }

    private static <T> List<T> libStatics(Class<?> holder, Class<T> type) {
        List<T> out = new ArrayList<>();
        try {
            for (Field field : holder.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) && type.isAssignableFrom(field.getType())) {
                    out.add(type.cast(field.get(null)));
                }
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private static void warmGl() {
        boolean sodium = sodiumLoaded();
        boolean interlock = GlCompat.SUPPORTS_FRAGMENT_INTERLOCK;
        OitPipelines.composite();
        OitPipelines.depth();
        for (OitMode mode : OitMode.values()) {
            if (mode == OitMode.OFF) {
                continue;
            }
            OitPipelines.chunkProducer(mode);
            for (BerFamily family : BerFamily.VALUES) {
                OitPipelines.berProducer(family, mode);
            }
            OitPipelines.layerProducer(mode);
            OitPipelines.weatherProducer(mode);
            if (sodium) {
                OitPipelines.chunkSodiumProducer(mode);
                OitPipelines.chunkSodiumProducer(mode, false);
                OitPipelines.chunkSodiumProducer(mode, true);
            }
        }
        for (OitInsertMode mode : OitInsertMode.values()) {
            if (mode == OitInsertMode.MLAB && !interlock) {
                continue;
            }
            OitPipelines.mlabResolve(mode);
            OitPipelines.chunkMlab(mode);
            for (BerFamily family : BerFamily.VALUES) {
                OitPipelines.berMlab(family, mode);
            }
            OitPipelines.weatherMlab(mode);
            if (sodium) {
                OitPipelines.chunkSodiumMlab(mode, false);
                OitPipelines.chunkSodiumMlab(mode, true);
            }
        }
        if (sodium) {
            TerrainPipelines.solid();
            TerrainPipelines.cutout();
        }

        IndirectPrograms programs = IndirectPrograms.get();
        programs.getCullingProgram();
        programs.getCullingPass2Program();
        List<Material> materials = warmMaterials();
        for (Material material : materials) {
            IndirectPipeline.uberPipelineFor(material);
            IndirectPipeline.uberDepthOnlyPipelineFor(material);
            if (material.transparency() != Transparency.OPAQUE) {
                for (OitMode mode : OitMode.values()) {
                    if (mode != OitMode.OFF) {
                        OitPipelines.uberProducer(material, mode);
                    }
                }
                for (OitInsertMode mode : OitInsertMode.values()) {
                    if (mode != OitInsertMode.MLAB || interlock) {
                        OitPipelines.uberMlab(material, mode);
                    }
                }
            }
        }
        for (InstanceType<?> type : STANDARD_TYPES) {
            CrumblingPipelines.pipeline(Materials.CRUMBLING, type, true);
        }
        if (GlCompat.SUPPORTS_TERRAIN_MESH) {
            MeshVisualDrawManager.warmUp(MESH_VISUAL_TYPES, STANDARD_TYPES, materials);
        }
    }

    private static void warmVk() {
        VkPrograms programs = VkPrograms.get();
        if (programs == null) {
            return;
        }
        boolean localRead = VkCaps.DYNAMIC_RENDERING_LOCAL_READ_NEGOTIATED;
        boolean interlock = VkCaps.FRAGMENT_SHADER_INTERLOCK_NEGOTIATED;
        programs.cullPipeline();
        programs.cullPass2Pipeline();
        programs.applyPipeline();
        programs.downsampleFirstPipeline();
        programs.downsampleSecondPipeline();

        VkOitPipelines oit = programs.oit();
        oit.compositePipeline();
        oit.depthPipeline(false);
        if (localRead) {
            oit.depthPipeline(true);
            for (OitMode mode : OitMode.values()) {
                if (mode == OitMode.OFF) {
                    continue;
                }
                oit.layerFoldedPipeline(mode);
                oit.weatherFoldedPipeline(mode);
                for (BerFamily family : BerFamily.VALUES) {
                    oit.berFoldedPipeline(family, mode);
                }
                oit.chunkFoldedPipeline(mode);
            }
        } else {
            for (OitMode mode : OitMode.values()) {
                if (mode == OitMode.OFF) {
                    continue;
                }
                OitPipelines.chunkProducer(mode);
                for (BerFamily family : BerFamily.VALUES) {
                    OitPipelines.berProducer(family, mode);
                }
                OitPipelines.layerProducer(mode);
                OitPipelines.weatherProducer(mode);
            }
        }
        for (OitInsertMode mode : OitInsertMode.values()) {
            if (mode == OitInsertMode.MLAB && !interlock) {
                continue;
            }
            oit.mlabResolvePipeline(mode);
            oit.chunkMlabPipeline(mode);
            for (BerFamily family : BerFamily.VALUES) {
                oit.berMlabPipeline(family, mode);
            }
            oit.weatherMlabPipeline(mode);
        }

        if (sodiumLoaded()) {
            VkTerrainPrograms terrain = programs.terrain();
            terrain.regionTestPipeline();
            terrain.sectionTestPipeline();
            terrain.commandBuilderPipeline();
            terrain.translucentOitCullPipeline();
            terrain.drawPipeline(false, VK12.VK_FORMAT_R8G8B8A8_UNORM, VK12.VK_FORMAT_D32_SFLOAT);
            terrain.drawPipeline(true, VK12.VK_FORMAT_R8G8B8A8_UNORM, VK12.VK_FORMAT_D32_SFLOAT);
            for (OitMode mode : OitMode.values()) {
                if (mode == OitMode.OFF) {
                    continue;
                }
                terrain.translucentProducerPipeline(mode, localRead);
            }
            for (OitInsertMode mode : OitInsertMode.values()) {
                if (mode == OitInsertMode.MLAB && !interlock) {
                    continue;
                }
                terrain.translucentMlabPipeline(mode);
            }
        }

        LightSmoothness smoothness = BackendConfig.INSTANCE.lightSmoothness();
        VkUberPipelines uber = programs.uber();
        List<Material> materials = warmMaterials();
        for (Material material : materials) {
            uber.drawPipeline(material, smoothness, VK12.VK_FORMAT_R8G8B8A8_UNORM, VK12.VK_FORMAT_D32_SFLOAT);
            if (material.transparency() != Transparency.OPAQUE) {
                for (OitMode mode : OitMode.values()) {
                    if (mode == OitMode.OFF) {
                        continue;
                    }
                    uber.oitProducerPipeline(material, smoothness, mode, localRead);
                }
                for (OitInsertMode mode : OitInsertMode.values()) {
                    if (mode != OitInsertMode.MLAB || interlock) {
                        uber.mlabProducerPipeline(mode, material, smoothness);
                    }
                }
            }
        }
        for (InstanceType<?> type : STANDARD_TYPES) {
            uber.crumblingPipeline(type, smoothness, VK12.VK_FORMAT_R8G8B8A8_UNORM, VK12.VK_FORMAT_D32_SFLOAT);
        }

        if (VkCaps.MESH_SHADER_NEGOTIATED) {
            VkMeshVisualPipelines mesh = programs.meshVisual();
            mesh.builderPipeline();
            for (InstanceType<?> type : STANDARD_TYPES) {
                mesh.crumblingPipeline(type, VK12.VK_FORMAT_R8G8B8A8_UNORM, VK12.VK_FORMAT_D32_SFLOAT);
            }
            for (InstanceType<?> type : MESH_VISUAL_TYPES) {
                for (Material material : materials) {
                    if (material.transparency() == Transparency.OPAQUE) {
                        mesh.solidPipeline(type, material, VK12.VK_FORMAT_R8G8B8A8_UNORM, VK12.VK_FORMAT_D32_SFLOAT);
                    } else {
                        for (OitMode mode : OitMode.values()) {
                            if (mode == OitMode.OFF) {
                                continue;
                            }
                            mesh.oitPipeline(type, material, mode, VK12.VK_FORMAT_D32_SFLOAT, localRead);
                        }
                        for (OitInsertMode mode : OitInsertMode.values()) {
                            if (mode != OitInsertMode.MLAB || interlock) {
                                mesh.mlabPipeline(type, material, mode, VK12.VK_FORMAT_D32_SFLOAT);
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean sodiumLoaded() {
        try {
            Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
