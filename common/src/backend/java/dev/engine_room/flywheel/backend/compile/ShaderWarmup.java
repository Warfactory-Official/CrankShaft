package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.CutoutShader;
import dev.engine_room.flywheel.api.material.FogShader;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.Backends;
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
import dev.engine_room.flywheel.lib.util.ShaderWarmupRegistry;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import org.lwjgl.vulkan.VK10;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Precompiles declared visual shader variants after every client resource listener has applied, before drawing resumes.
 * Downstream model initialization and vanilla's device pipeline-cache clear must precede this step.
 */
public final class ShaderWarmup {
    // Registered by modules :common cannot reference (the :meshlet tiers); run after the common warms.
    private static final List<Runnable> HOOKS = new ArrayList<>();
    private static final List<Runnable> VK_HOOKS = new ArrayList<>();

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

    /**
     * Register during backend initialization. Runs as a Vulkan compiler job after resource apply, with exclusive
     * ownership of the hook's pipeline caches. All jobs and their deferred cleanup finish before rendering resumes.
     */
    public static void registerVk(Runnable hook) {
        VK_HOOKS.add(hook);
    }

    /**
     * Called once at successful resource-reload completion, before returning to world rendering.
     */
    public static void warm() {
        long start = System.nanoTime();
        seedRegistrations();
        if (!BackendManager.isBackendOn()) return;
        if (VkContext.isVulkanHost()) {
            warmVk();
        } else if (!ShadersModHelper.isShaderPackInUse()) {
            if (IndirectPrograms.allLoaded()) RenderPipelineCompiler.warm(ShaderWarmup::warmGl);
            else if (BackendManager.currentBackend() == Backends.INSTANCING)
                RenderPipelineCompiler.warm(ShaderWarmup::warmInstancing);
        }
        for (Runnable hook : HOOKS) {
            hook.run();
        }
        if (VkContext.isVulkanHost()) VkPipelineCaches.save();
        FlwPrograms.LOGGER.info("pipeline warm-up took {}ms ({} types, {} material variants)",
                (System.nanoTime() - start) / 1_000_000, InstanceTypeIds.count(), warmMaterials().size());
    }

    /**
     * Seed every known type and fragment selector on the render thread before compiling generation-keyed programs.
     */
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
        // Built-in material initialization must precede the downstream snapshot.
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

    private static void warmGl() {
        boolean sodium = sodiumLoaded();
        boolean interlock = GlCompat.SUPPORTS_FRAGMENT_INTERLOCK;
        OitPipelines.composite(false);
        OitPipelines.composite(true);
        OitPipelines.emission();
        OitPipelines.oitDepth();
        OitPipelines.mlabNearestDepth();
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

        if (BackendManager.currentBackend() == Backends.INSTANCING) {
            warmInstancing();
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
                    for (OitInsertMode mode : OitInsertMode.values()) {
                        if (mode != OitInsertMode.MLAB || interlock) {
                            OitPipelines.uberMlab(material, mode, embedded);
                        }
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
        boolean localRead = VkCaps.DYNAMIC_RENDERING_LOCAL_READ_NEGOTIATED;
        boolean interlock = VkCaps.FRAGMENT_SHADER_INTERLOCK_NEGOTIATED;
        LightSmoothness smoothness = BackendConfig.INSTANCE.lightSmoothness();
        List<Material> materials = warmMaterials();
        // Populate embedded-source discovery before worker compilation reads the same immutable shader inputs.
        materials.forEach(RenderPassShaders::readsEmbedded);
        VkOitPipelines oit = programs.oit();
        VkUberPipelines uber = programs.uber();
        List<Runnable> jobs = new ArrayList<>();
        jobs.add(() -> {
            programs.cullPipeline();
            programs.cullPass2Pipeline();
            programs.applyPipeline();
            programs.downsampleFirstPipeline();
            programs.downsampleSecondPipeline();
        });
        // The RHI replay fallback belongs to Mojang's render-thread caches, not our worker-owned pipeline families.
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
            oit.mlabNearestDepthPipeline();
            if (localRead) {
                for (OitMode mode : OitMode.values()) {
                    if (mode == OitMode.OFF) continue;
                    oit.layerFoldedPipeline(mode);
                    oit.weatherFoldedPipeline(mode);
                    for (BerFamily family : BerFamily.VALUES) oit.berFoldedPipeline(family, mode);
                    oit.chunkFoldedPipeline(mode);
                }
            }
            for (OitInsertMode mode : OitInsertMode.values()) {
                if (mode == OitInsertMode.MLAB && !interlock) continue;
                oit.mlabResolvePipeline(mode);
                oit.chunkMlabPipeline(mode);
                for (BerFamily family : BerFamily.VALUES) oit.berMlabPipeline(family, mode);
                oit.weatherMlabPipeline(mode);
            }
        });
        if (sodiumLoaded()) jobs.add(() -> {
            VkTerrainPrograms terrain = programs.terrain();
            terrain.cullPipeline();
            terrain.translucentOitCullPipeline();
            terrain.drawPipeline(false, VK10.VK_FORMAT_R8G8B8A8_UNORM, VK10.VK_FORMAT_D32_SFLOAT);
            terrain.drawPipeline(true, VK10.VK_FORMAT_R8G8B8A8_UNORM, VK10.VK_FORMAT_D32_SFLOAT);
            for (OitMode mode : OitMode.values()) {
                if (mode != OitMode.OFF) terrain.translucentProducerPipeline(mode, localRead);
            }
            for (OitInsertMode mode : OitInsertMode.values()) {
                if (mode != OitInsertMode.MLAB || interlock) terrain.translucentMlabPipeline(mode);
            }
        });
        // These four jobs own disjoint maps in VkUberPipelines; no draw-time cache synchronization is added.
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
                        if (mode != OitMode.OFF)
                            uber.oitProducerPipeline(material, embedded, smoothness, mode, localRead);
                    }
                }
            }
        });
        jobs.add(() -> {
            for (Material material : materials) {
                if (!OitTransparency.orderIndependent(material)) continue;
                for (boolean embedded : new boolean[]{false, true}) {
                    for (OitInsertMode mode : OitInsertMode.values()) {
                        if (mode != OitInsertMode.MLAB || interlock)
                            uber.mlabProducerPipeline(mode, material, embedded, smoothness);
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
        if (VkCaps.MESH_SHADER_NEGOTIATED && Backend.REGISTRY.getIdOrThrow(BackendManager.currentBackend())
                                                             .getPath().equals("vk_mesh_shader")) jobs.add(() -> {
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
                            if (mode != OitMode.OFF)
                                mesh.oitPipeline(type, material, false, mode, VK10.VK_FORMAT_D32_SFLOAT, localRead);
                        }
                        for (OitInsertMode mode : OitInsertMode.values()) {
                            if (mode != OitInsertMode.MLAB || interlock)
                                mesh.mlabPipeline(type, material, false, mode, VK10.VK_FORMAT_D32_SFLOAT);
                        }
                    }
                }
            }
        });
        jobs.addAll(VK_HOOKS);
        VkCompilationTasks.run(jobs);
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
