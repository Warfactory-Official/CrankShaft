package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.CutoutShader;
import dev.engine_room.flywheel.api.material.FogShader;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.Backends;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.MaterialShaderIndices;
import dev.engine_room.flywheel.backend.engine.BerFamily;
import dev.engine_room.flywheel.backend.engine.CrumblingPipelines;
import dev.engine_room.flywheel.backend.engine.OitTransparency;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectPipeline;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.engine.indirect.MeshVisualDrawManager;
import dev.engine_room.flywheel.backend.engine.indirect.OitPipelines;
import dev.engine_room.flywheel.backend.engine.instancing.InstancingPipeline;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainPipelines;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.VkPipelineCaches;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.FogShaders;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.util.ShaderWarmupRegistry;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Render-thread warmup after resource apply, downstream model initialization and device-cache clear. */
public final class ShaderWarmup {
    private static final List<WarmHook> HOOKS = new ArrayList<>();
    private static final List<WarmHook> VK_HOOKS = new ArrayList<>();

    private static final List<InstanceType<?>> WARM_TYPES = List.of(InstanceTypes.TRANSFORMED, InstanceTypes.POSED,
            InstanceTypes.ORIENTED, InstanceTypes.CLIP_TRANSFORMED, InstanceTypes.UV_TRANSFORMED,
            InstanceTypes.BILLBOARD, InstanceTypes.GLYPH, InstanceTypes.LEASH, InstanceTypes.SHADOW);

    private static final List<InstanceType<?>> STANDARD_TYPES = List.of(InstanceTypes.TRANSFORMED,
            InstanceTypes.POSED, InstanceTypes.ORIENTED);

    private static final List<InstanceType<?>> MESH_VISUAL_TYPES = List.of(InstanceTypes.TRANSFORMED,
            InstanceTypes.POSED, InstanceTypes.ORIENTED, InstanceTypes.CLIP_TRANSFORMED);

    private ShaderWarmup() {
    }

    /** Backend-init registration; render-thread hook after common warmup. */
    public static void register(Runnable hook) {
        HOOKS.add(new WarmHook(null, hook));
    }

    /** {@link #register(Runnable)}; skipped after feature rejection. */
    public static void register(ProgramAvailability.Feature feature, Runnable hook) {
        HOOKS.add(new WarmHook(feature, hook));
    }

    /** Backend-init registration; worker-owned pipeline caches; jobs/cleanup joined before rendering resumes. */
    public static void registerVk(Runnable hook) {
        VK_HOOKS.add(new WarmHook(null, hook));
    }

    /** {@link #registerVk(Runnable)}; feature rejection published after workers join. */
    public static void registerVk(ProgramAvailability.Feature feature, Runnable hook) {
        VK_HOOKS.add(new WarmHook(feature, hook));
    }

    private record WarmHook(ProgramAvailability.@Nullable Feature feature, Runnable action) {
        void run() {
            if (feature == null) action.run();
            else RenderPipelineCompiler.feature(feature, action);
        }
    }

    public static void warm() {
        long start = System.nanoTime();
        MojImportPreprocessor.clearImportContents();
        int sourceHits = GeneratedSourceCache.hits;
        int sourceMisses = GeneratedSourceCache.misses;
        seedRegistrations();
        if (!BackendManager.isBackendOn()) return;
        if (VkContext.isVulkanHost()) {
            warmVk();
        } else if (!ShadersModHelper.isShaderPackInUse()) {
            if (IndirectPrograms.allLoaded()) RenderPipelineCompiler.warm(ShaderWarmup::warmGl);
            else if (BackendManager.currentBackend() == Backends.INSTANCING)
                RenderPipelineCompiler.warm(ShaderWarmup::warmInstancing);
        }
        for (WarmHook hook : HOOKS) hook.run();
        if (VkContext.isVulkanHost()) VkPipelineCaches.save();
        FlwPrograms.LOGGER.info("pipeline warm-up took {}ms ({} types, {} material variants, {} cached / {} generated sources)",
                (System.nanoTime() - start) / 1_000_000, InstanceTypeIds.count(), warmMaterials().size(),
                GeneratedSourceCache.hits - sourceHits, GeneratedSourceCache.misses - sourceMisses);
    }

    /** Render thread; precedes generation-keyed compilation. */
    public static void seedRegistrations() {
        for (InstanceType<?> type : WARM_TYPES) {
            InstanceTypeIds.id(type);
        }
        for (CutoutShader cutout : libStatics(CutoutShaders.class, CutoutShader.class)) {
            MaterialShaderIndices.cutoutIndex(cutout);
        }
        for (FogShader fog : libStatics(FogShaders.class, FogShader.class)) {
            MaterialShaderIndices.fogIndex(fog);
        }
        for (InstanceType<?> type : ShaderWarmupRegistry.types()) {
            InstanceTypeIds.id(type);
        }
        for (Material material : warmMaterials()) {
            MaterialShaderIndices.cutoutIndex(material.cutout());
            MaterialShaderIndices.fogIndex(material.fog());
        }
    }

    private static List<Material> warmMaterials() {
        libStatics(Materials.class, Material.class);
        return ShaderWarmupRegistry.materials();
    }

    private static void warmInstancing() {
        warmInstancing(true);
    }

    private static void warmInstancing(boolean portableCoverage) {
        if (portableCoverage) {
            warmWaveletFullscreen();
            warmWaveletReplays(sodiumLoaded());
            for (InstanceType<?> type : ShaderWarmupRegistry.types()) {
                CrumblingPipelines.pipeline(Materials.CRUMBLING, type, false);
            }
        }
        List<Material> materials = warmMaterials();
        for (InstanceType<?> type : ShaderWarmupRegistry.types()) {
            for (Material material : materials) {
                for (boolean embedded : new boolean[]{false, true}) {
                    InstancingPipeline.pipelineFor(material, type, embedded);
                    if (OitTransparency.orderIndependent(material)) {
                        for (OitMode mode : OitMode.values()) {
                            if (mode != OitMode.OFF) OitPipelines.producer(material, type, mode, false, embedded);
                        }
                    }
                }
            }
        }
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

    private static void warmWaveletFullscreen() {
        OitPipelines.composite(false);
        OitPipelines.composite(true);
        OitPipelines.emission();
        OitPipelines.oitDepth();
    }

    private static void warmWaveletReplays(boolean sodium) {
        for (OitMode mode : OitMode.values()) {
            if (mode == OitMode.OFF) continue;
            OitPipelines.chunkProducer(mode);
            for (BerFamily family : BerFamily.VALUES) OitPipelines.berProducer(family, mode);
            OitPipelines.layerProducer(mode);
            OitPipelines.weatherProducer(mode);
            if (sodium) {
                OitPipelines.chunkSodiumProducer(mode);
                if (GlCompat.SUPPORTS_INDIRECT) {
                    OitPipelines.chunkSodiumProducer(mode, false);
                    OitPipelines.chunkSodiumProducer(mode, true);
                }
            }
        }
    }

    private static void warmGl() {
        boolean sodium = sodiumLoaded();
        List<OitInsertMode> insertModes = Arrays.stream(OitInsertMode.values())
                .filter(OitConfig::supportsInsertMode).toList();
        warmWaveletFullscreen();
        for (OitInsertMode mode : insertModes) {
            RenderPipelineCompiler.feature(ProgramAvailability.insert(mode), OitPipelines::mlabNearestDepth);
        }
        warmWaveletReplays(sodium);
        for (OitInsertMode mode : insertModes) {
            RenderPipelineCompiler.feature(ProgramAvailability.insert(mode), () -> {
                OitPipelines.mlabResolve(mode);
                OitPipelines.chunkMlab(mode);
                for (BerFamily family : BerFamily.VALUES) OitPipelines.berMlab(family, mode);
                OitPipelines.weatherMlab(mode);
                if (sodium) {
                    OitPipelines.chunkSodiumMlab(mode, false);
                    OitPipelines.chunkSodiumMlab(mode, true);
                }
            });
        }
        if (sodium) {
            RenderPipelineCompiler.feature(ProgramAvailability.Feature.OPAQUE_TERRAIN, () -> {
                TerrainPipelines.solid();
                TerrainPipelines.cutout();
            });
        }

        if (BackendManager.currentBackend() == Backends.INSTANCING) {
            warmInstancing(false);
            return;
        }
        IndirectPrograms programs = IndirectPrograms.get();
        programs.getCullingProgram();
        programs.getCullingPass2Program();
        programs.getCullingFrustumProgram();
        List<Material> materials = warmMaterials();
        for (Material material : materials) {
            for (boolean embedded : new boolean[]{false, true}) {
                IndirectPipeline.uberPipelineFor(material, embedded);
                if (OitTransparency.orderIndependent(material)) {
                    for (OitMode mode : OitMode.values()) {
                        if (mode != OitMode.OFF) {
                            OitPipelines.uberProducer(material, mode, embedded);
                        }
                    }
                    for (OitInsertMode mode : insertModes) {
                        RenderPipelineCompiler.feature(ProgramAvailability.insert(mode),
                                () -> OitPipelines.uberMlab(material, mode, embedded));
                    }
                }
            }
        }
        for (InstanceType<?> type : STANDARD_TYPES) {
            CrumblingPipelines.pipeline(Materials.CRUMBLING, type, true);
        }
        if (GlCompat.SUPPORTS_TERRAIN_MESH && Backend.REGISTRY.getIdOrThrow(BackendManager.currentBackend())
                                                              .getPath().equals("gl_mesh_shader")) {
            MeshVisualDrawManager.warmUp(MESH_VISUAL_TYPES, STANDARD_TYPES, materials);
        }
    }

    private static void warmVk() {
        VkPrograms programs = VkPrograms.get();
        if (programs == null) return;
        boolean localRead = programs.localRead();
        List<OitInsertMode> insertModes = Arrays.stream(OitInsertMode.values())
                .filter(OitConfig::supportsInsertMode).toList();
        LightSmoothness smoothness = BackendConfig.INSTANCE.lightSmoothness();
        List<Material> materials = warmMaterials();
        materials.forEach(RenderPassShaders::readsEmbedded);
        VkOitPipelines oit = programs.oit();
        VkUberPipelines uber = programs.uber();
        List<Runnable> jobs = new ArrayList<>();
        jobs.add(() -> {
            if (programs.instanceRoute() == VkPrograms.InstanceRoute.INDIRECT) {
                programs.cullPipeline();
                programs.cullPass2Pipeline();
                programs.applyPipeline();
            }
            if (programs.hiZRoute() != VkPrograms.HiZRoute.NONE) {
                programs.downsampleFirstPipeline();
                programs.downsampleSecondPipeline();
            }
        });
        if (!localRead) {
            for (OitMode mode : OitMode.values()) {
                if (mode == OitMode.OFF) continue;
                OitPipelines.chunkProducer(mode);
                for (BerFamily family : BerFamily.VALUES) OitPipelines.berProducer(family, mode);
                OitPipelines.layerProducer(mode);
                OitPipelines.weatherProducer(mode);
            }
        }
        jobs.add(() -> {
            oit.compositePipeline(false);
            oit.compositePipeline(true);
            oit.emissionPipeline();
            oit.oitDepthPipeline();
            for (OitInsertMode mode : insertModes) {
                oit.mlabNearestDepthPipeline(mode);
            }
            if (localRead) {
                ProgramAvailability.run(ProgramAvailability.Feature.LOCAL_READ, () -> {
                    for (OitMode mode : OitMode.values()) {
                        if (mode == OitMode.OFF) continue;
                        oit.layerFoldedPipeline(mode);
                        oit.weatherFoldedPipeline(mode);
                        for (BerFamily family : BerFamily.VALUES) oit.berFoldedPipeline(family, mode);
                        oit.chunkFoldedPipeline(mode);
                    }
                });
            }
            for (OitInsertMode mode : insertModes) {
                ProgramAvailability.run(ProgramAvailability.insert(mode), () -> {
                    oit.mlabResolvePipeline(mode);
                    oit.chunkMlabPipeline(mode);
                    for (BerFamily family : BerFamily.VALUES) oit.berMlabPipeline(family, mode);
                    oit.weatherMlabPipeline(mode);
                });
            }
        });
        if (sodiumLoaded()) jobs.add(() -> {
            VkTerrainPrograms terrain = programs.terrain();
            if (programs.usesGpuTerrain()) {
                ProgramAvailability.run(ProgramAvailability.Feature.OPAQUE_TERRAIN, () -> {
                    terrain.cullPipeline();
                    terrain.drawPipeline(false, VK10.VK_FORMAT_R8G8B8A8_UNORM, VK10.VK_FORMAT_D32_SFLOAT);
                    terrain.drawPipeline(true, VK10.VK_FORMAT_R8G8B8A8_UNORM, VK10.VK_FORMAT_D32_SFLOAT);
                });
            }
            if (programs.usesGpuTranslucentTerrain()) {
                ProgramAvailability.run(ProgramAvailability.Feature.TERRAIN_OIT, () -> {
                    terrain.translucentOitCullPipeline();
                    warmVkTerrainProducers(terrain, localRead, insertModes, false);
                });
            } else {
                warmVkTerrainProducers(terrain, localRead, insertModes, true);
            }
        });
        jobs.add(() -> {
            for (Material material : materials) {
                for (boolean embedded : new boolean[]{false, true}) {
                    uber.drawPipeline(material, embedded, smoothness, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                            VK10.VK_FORMAT_D32_SFLOAT);
                }
            }
        });
        jobs.add(() -> {
            for (Material material : materials) {
                if (!OitTransparency.orderIndependent(material)) continue;
                for (boolean embedded : new boolean[]{false, true}) {
                    for (OitMode mode : OitMode.values()) {
                        if (mode == OitMode.OFF) continue;
                        if (localRead) {
                            ProgramAvailability.run(ProgramAvailability.Feature.LOCAL_READ,
                                    () -> uber.oitProducerPipeline(material, embedded, smoothness, mode, true));
                        } else uber.oitProducerPipeline(material, embedded, smoothness, mode, false);
                    }
                }
            }
        });
        jobs.add(() -> {
            for (Material material : materials) {
                if (!OitTransparency.orderIndependent(material)) continue;
                for (boolean embedded : new boolean[]{false, true}) {
                    for (OitInsertMode mode : insertModes) {
                        ProgramAvailability.run(ProgramAvailability.insert(mode),
                                () -> uber.mlabProducerPipeline(mode, material, embedded, smoothness));
                    }
                }
            }
        });
        jobs.add(() -> {
            for (InstanceType<?> type : STANDARD_TYPES) {
                uber.crumblingPipeline(Materials.CRUMBLING, type, smoothness,
                        VK10.VK_FORMAT_R8G8B8A8_UNORM, VK10.VK_FORMAT_D32_SFLOAT);
            }
        });
        if (VkCaps.MESH_SHADER_NEGOTIATED && ProgramAvailability.allows(ProgramAvailability.Feature.MESH)
                && Backend.REGISTRY.getIdOrThrow(BackendManager.currentBackend())
                                   .getPath().equals("vk_mesh_shader")) jobs.add(() -> ProgramAvailability.run(
                ProgramAvailability.Feature.MESH, () -> {
            VkMeshVisualPipelines mesh = programs.meshVisual();
            mesh.builderPipeline();
            for (InstanceType<?> type : STANDARD_TYPES) {
                mesh.crumblingPipeline(Materials.CRUMBLING, type, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                        VK10.VK_FORMAT_D32_SFLOAT);
            }
            for (InstanceType<?> type : MESH_VISUAL_TYPES) {
                for (Material material : materials) {
                    if (!OitTransparency.orderIndependent(material)) {
                        mesh.solidPipeline(type, material, false, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                                VK10.VK_FORMAT_D32_SFLOAT);
                    } else {
                        for (OitMode mode : OitMode.values()) {
                            if (mode == OitMode.OFF) continue;
                            if (localRead) {
                                ProgramAvailability.run(ProgramAvailability.Feature.LOCAL_READ,
                                        () -> mesh.oitPipeline(type, material, false, mode,
                                                VK10.VK_FORMAT_D32_SFLOAT, true));
                            } else mesh.oitPipeline(type, material, false, mode, VK10.VK_FORMAT_D32_SFLOAT, false);
                        }
                        for (OitInsertMode mode : insertModes) {
                            ProgramAvailability.run(ProgramAvailability.insert(mode),
                                    () -> mesh.mlabPipeline(type, material, false, mode, VK10.VK_FORMAT_D32_SFLOAT));
                        }
                    }
                }
            }
        }));
        for (WarmHook hook : VK_HOOKS) jobs.add(hook::run);
        VkCompilationTasks.run(jobs);
    }

    private static void warmVkTerrainProducers(VkTerrainPrograms terrain, boolean localRead,
                                               List<OitInsertMode> insertModes, boolean classic) {
        Runnable wavelet = () -> {
            for (OitMode mode : OitMode.values()) {
                if (mode == OitMode.OFF) continue;
                if (classic) terrain.classicTranslucentProducerPipeline(mode, localRead);
                else terrain.translucentProducerPipeline(mode, localRead);
            }
        };
        if (localRead) ProgramAvailability.run(ProgramAvailability.Feature.LOCAL_READ, wavelet);
        else wavelet.run();
        for (OitInsertMode mode : insertModes) {
            ProgramAvailability.run(ProgramAvailability.insert(mode), () -> {
                if (classic) terrain.classicTranslucentMlabPipeline(mode);
                else terrain.translucentMlabPipeline(mode);
            });
        }
    }

    public static void retry(ProgramAvailability.Feature feature) {
        RenderSystem.assertOnRenderThread();
        RendererReloadCache.onReloadLevelRenderer();
        if (VkContext.isVulkanHost()) {
            VkPrograms programs = VkPrograms.get();
            if (programs != null) VkPrograms.rejectFeature(feature);
        } else {
            RenderSystem.getDevice().clearPipelineCache();
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
