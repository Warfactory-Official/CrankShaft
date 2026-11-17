package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.CutoutShader;
import dev.engine_room.flywheel.api.material.FogShader;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.backend.BackendConfig;
import net.minecraft.client.Minecraft;
import dev.engine_room.flywheel.backend.Backends;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.SodiumClassLoadCheck;
import dev.engine_room.flywheel.backend.MaterialShaderIndices;
import dev.engine_room.flywheel.backend.engine.BerFamily;
import dev.engine_room.flywheel.backend.engine.CrumblingPipelines;
import dev.engine_room.flywheel.backend.engine.OitTransparency;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectPipeline;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.engine.indirect.MeshVisualDrawManager;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.indirect.OitPipelines;
#if MC < 26.3
import dev.engine_room.flywheel.backend.engine.indirect.FabulousOitPipelines;
#endif
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
import dev.engine_room.flywheel.lib.material.ShaderVariant;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.util.ShaderWarmupRegistry;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Render-thread warmup after resource apply, downstream model initialization and device-cache clear.
 */
public final class ShaderWarmup {
    private static volatile boolean initialized;
    private static boolean reloading;
    private static int fallingBack;
    private static boolean engaging;
    private static Consumer<Runnable> recovery;
    private static final boolean[] UNEMBEDDED = {false};
    private static final boolean[] EMBEDDED_VARIANTS = {false, true};
    private static final List<WarmHook> HOOKS = new ArrayList<>();
    private static final List<WarmHook> VK_HOOKS = new ArrayList<>();
    private static final List<Runnable> GUEST_HOOKS = new ArrayList<>();
    private static Set<ShaderVariant> seededVariants = Set.of();
    // Each skipped material and instance type classified once per seeding. Render thread.
    private static final Map<Material, Map<InstanceType<?>, Skip>> SKIPS = new IdentityHashMap<>();
    private static Set<InstanceType<?>> seededTypes = Set.of();
    #if MC >= 26.3
    private static final List<Runnable> NATIVE_RESOURCES = new ArrayList<>();
    #endif

    private static final List<InstanceType<?>> WARM_TYPES = List.of(InstanceTypes.TRANSFORMED, InstanceTypes.POSED,
            InstanceTypes.ORIENTED, InstanceTypes.CLIP_TRANSFORMED, InstanceTypes.UV_TRANSFORMED,
            InstanceTypes.BILLBOARD, InstanceTypes.GLYPH, InstanceTypes.LEASH, InstanceTypes.SHADOW);

    private ShaderWarmup() {
    }

    /**
     * Backend-init registration; render-thread hook after common warmup, retaining reload setup in on-demand mode.
     */
    public static void register(Runnable hook) {
        HOOKS.add(new WarmHook(null, hook));
    }

    /**
     * {@link #register(Runnable)}; skipped after feature rejection.
     */
    public static void register(ProgramAvailability.Feature feature, Runnable hook) {
        HOOKS.add(new WarmHook(feature, hook));
    }

    /**
     * Backend-init registration; worker-owned pipeline caches; jobs/cleanup joined before rendering resumes.
     */
    public static void registerVk(Runnable hook) {
        VK_HOOKS.add(new WarmHook(null, hook));
    }

    /**
     * Backend-init registration; prepares a guest backend selected under a shader pack outside resource loading.
     */
    public static void registerGuest(Runnable hook) {
        GUEST_HOOKS.add(hook);
    }

    /**
     * {@link #registerVk(Runnable)}; feature rejection published after workers join.
     */
    public static void registerVk(ProgramAvailability.Feature feature, Runnable hook) {
        VK_HOOKS.add(new WarmHook(feature, hook));
    }

    #if MC >= 26.3
    /**
     * Client-init registration, pre-resource-load; declares device resources in the render-thread loading batch.
     */
    public static void registerNativeResources(Runnable declarations) {
        NATIVE_RESOURCES.add(declarations);
    }

    private static void warmNativeResources() {
        for (Runnable declarations : NATIVE_RESOURCES) declarations.run();
    }
    #endif

    private record WarmHook(ProgramAvailability.@Nullable Feature feature, Runnable action) {
        void run() {
            if (feature == null) action.run();
            else RenderPipelineCompiler.feature(feature, action);
        }
    }

    /**
     * Client initialization installs the loading and configuration failure policy.
     */
    public static void registerRecovery(Consumer<Runnable> handler) {
        recovery = handler;
    }

    /**
     * Configuration publication and selected-family preparation complete together on the render thread.
     */
    public static void configure(Runnable changes) {
        if (initialized && !RenderSystem.isOnRenderThread()) {
            Minecraft.getInstance().schedule(() -> configure(changes));
            return;
        }
        changes.run();
        if (initialized && BackendManager.isBackendOn() && !ShadersModHelper.isShaderPackInUse()) {
            recovery.accept(ShaderWarmup::warm);
        }
    }

    /**
     * Vanilla's shader apply, which releases every published program, or {@link FlwPrograms#reload}; engines draw
     * nothing ({@link #pending}) until the loading warm-up ({@link #reloaded}). Render thread.
     */
    public static void invalidated() {
        reloading = true;
    }

    /**
     * Resource loading's warm-up finished. Render thread.
     */
    public static void reloaded() {
        reloading = false;
    }

    /**
     * Between a resource reload's program release and its warm-up, or a draw-time fallback's engagement and its
     * warm-up, when a draw would wait for a compile. Render thread.
     */
    public static boolean pending() {
        return reloading || engaging;
    }

    /**
     * Runs a draw's backend fallback; the backend it engages warms from the client task queue, off the draw's stack,
     * and engines draw nothing until then. Render thread.
     */
    public static void fallingBack(Runnable fallback) {
        fallingBack++;
        try {
            fallback.run();
        } finally {
            fallingBack--;
        }
    }

    /**
     * A backend selected outside resource loading, warmed before its first draw; under a shader pack, through the
     * {@link #registerGuest} warm-ups. A reload in progress warms its own selection. Render thread.
     */
    public static void engaged() {
        if (!initialized || reloading || !BackendManager.isBackendOn()) return;
        if (fallingBack == 0) {
            warmEngaged();
            return;
        }
        engaging = true;
        Minecraft.getInstance().schedule(() -> {
            try {
                if (!reloading && BackendManager.isBackendOn()) warmEngaged();
            } finally {
                engaging = false;
            }
        });
    }

    private static void warmEngaged() {
        recovery.accept(ShadersModHelper.isShaderPackInUse() ? ShaderWarmup::warmGuests : ShaderWarmup::warm);
    }

    private static void warmGuests() {
        for (Runnable hook : GUEST_HOOKS) hook.run();
    }

    public static void warm() {
        initialized = true;
        long start = System.nanoTime();
        #if MC < 26.3
        MojImportPreprocessor.clearImportContents();
        #endif
        int sourceHits = GeneratedSourceCache.hits;
        int sourceMisses = GeneratedSourceCache.misses;
        seedRegistrations();
        if (BackendConfig.INSTANCE.shaderCompilation() == ShaderCompilationMode.ON_DEMAND) {
            #if MC >= 26.3
            RenderPipelineCompiler.warm(ShaderWarmup::warmNativeResources);
            #endif
            // Pack hooks also reset resource ownership; hooks honor the mode before speculative compilation.
            for (WarmHook hook : HOOKS) hook.run();
            #if MC >= 26.3
            NativeOitWarmup.loadingComplete();
            #endif
            FlwPrograms.LOGGER.info("Shader warm-up mode: on_demand");
            return;
        }
        if (!BackendManager.isBackendOn()) {
            #if MC >= 26.3
            RenderPipelineCompiler.warm(ShaderWarmup::warmNativeResources);
            NativeOitWarmup.loadingComplete();
            #endif
            return;
        }
        if (VkContext.isVulkanHost()) {
            VkAsyncBuilds.warm(ShaderWarmup::warmVk);
        } else if (!ShadersModHelper.isShaderPackInUse()) {
            if (IndirectPrograms.allLoaded()) RenderPipelineCompiler.warm(ShaderWarmup::warmGl);
            else if (BackendManager.currentBackend() == Backends.INSTANCING)
                RenderPipelineCompiler.warm(ShaderWarmup::warmInstancing);
        }
        for (WarmHook hook : HOOKS) hook.run();
        if (VkContext.isVulkanHost()) VkPipelineCaches.save();
        #if MC >= 26.3
        NativeOitWarmup.loadingComplete();
        #endif
        FlwPrograms.LOGGER.info(
                "pipeline warm-up took {}ms ({} types, {} shader variants, {} cached / {} generated sources)",
                (System.nanoTime() - start) / 1_000_000, InstanceTypeIds.count(), warmMaterials().size(),
                GeneratedSourceCache.hits - sourceHits, GeneratedSourceCache.misses - sourceMisses);
    }

    /**
     * Render thread; precedes generation-keyed compilation.
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
        List<InstanceType<?>> types = ShaderWarmupRegistry.types();
        for (InstanceType<?> type : types) {
            InstanceTypeIds.id(type);
        }
        Set<ShaderVariant> variants = new TreeSet<>(ShaderVariant.ORDER);
        for (Material material : warmMaterials()) {
            MaterialShaderIndices.cutoutIndex(material.cutout());
            MaterialShaderIndices.fogIndex(material.fog());
            variants.add(ShaderVariant.of(material));
        }
        Set<InstanceType<?>> warmTypes = new HashSet<>(WARM_TYPES);
        warmTypes.addAll(types);
        seededVariants = variants;
        seededTypes = warmTypes;
        SKIPS.clear();
    }

    /**
     * A draw skipped because no published program covers it yet: a {@link ShaderWarmupRegistry#misses} miss when its
     * variant or instance type was not seeded, else a {@link ShaderWarmupRegistry#skips} skip. Render thread; skip
     * branches only.
     */
    public static void skipped(Material material, InstanceType<?> type) {
        Map<InstanceType<?>, Skip> byType = SKIPS.get(material);
        if (byType == null) SKIPS.put(material, byType = new IdentityHashMap<>());
        Skip skip = byType.get(type);
        if (skip == null) byType.put(type, skip = Skip.of(material, type));
        if (skip.unregistered() == null) {
            ShaderWarmupRegistry.recordSkip();
        } else if (ShaderWarmupRegistry.recordMiss(skip.variant())) {
            FlwPrograms.LOGGER.warn("Draws skipped until their program builds: {} (instance type {}): {} not "
                            + "registered in ShaderWarmupRegistry before the last shader warm-up", skip.variant(),
                    type.vertexShader(), skip.unregistered());
        }
    }

    // unregistered: what the seeding lacked, null for a registered variant and type.
    private record Skip(ShaderVariant variant, @Nullable String unregistered) {
        static Skip of(Material material, InstanceType<?> type) {
            ShaderVariant variant = ShaderVariant.of(material);
            boolean variantSeeded = seededVariants.contains(variant);
            boolean typeSeeded = seededTypes.contains(type);
            return new Skip(variant, variantSeeded && typeSeeded ? null : variantSeeded ? "instance type"
                    : typeSeeded ? "shader variant" : "shader variant and instance type");
        }
    }

    #if MC >= 26.3
    /**
     * Material declarations for native pass preparation at loading/configuration boundaries; render thread only.
     */
    public static List<Material> nativeWarmMaterials() {
        return warmMaterials();
    }
    #endif

    private static List<Material> warmMaterials() {
        libStatics(Materials.class, Material.class);
        return ShaderWarmupRegistry.materials();
    }

    private static void warmInstancing() {
        warmInstancing(true);
    }

    private static void warmInstancing(boolean portableCoverage) {
        #if MC >= 26.3
        if (portableCoverage) warmNativeResources();
        #endif
        List<OitInsertMode> insertModes = insertModes();
        if (portableCoverage) {
            if (insertModes.isEmpty()) {
                warmWaveletFullscreen();
                warmWaveletReplays(SodiumClassLoadCheck.PRESENT);
            } else {
                warmInsertReplays(insertModes, SodiumClassLoadCheck.PRESENT);
            }
        }
        #if MC >= 26.3
        if (portableCoverage) NativeOitWarmup.declareSelected();
        #endif
        List<Material> materials = warmMaterials();
        for (InstanceType<?> type : ShaderWarmupRegistry.types()) {
            CrumblingPipelines.pipeline(Materials.CRUMBLING, type, false);
        }
        for (InstanceType<?> type : ShaderWarmupRegistry.types()) {
            for (Material material : materials) {
                for (boolean embedded : new boolean[]{false, true}) {
                    if (OitTransparency.orderIndependent(material)) {
                        if (insertModes.isEmpty()) {
                            OitPipelines.producer(material, type, OitMode.EVALUATE, embedded, true);
                        }
                        for (OitInsertMode mode : insertModes) {
                            RenderPipelineCompiler.feature(ProgramAvailability.insert(mode),
                                    () -> OitPipelines.instancedMlab(material, type, mode, embedded, true));
                        }
                    } else {
                        InstancingPipeline.pipelineFor(material, type, embedded, true);
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
        if (OitFramebuffer.SPLIT_INSTANCE_BOUNDS) OitPipelines.boundsMerge();
    }

    private static void warmWaveletReplays(boolean sodium) {
        for (OitMode mode : OitMode.values()) {
            if (mode == OitMode.OFF) continue;
            OitPipelines.chunkProducer(mode);
            #if MC >= 26.3
            OitPipelines.chunkProducer(mode, true);
            #endif
            for (BerFamily family : BerFamily.VALUES) OitPipelines.berProducer(family, mode);
            #if MC < 26.3
            FabulousOitPipelines.layerProducer(mode);
            FabulousOitPipelines.weatherProducer(mode);
            #endif
            if (sodium) {
                OitPipelines.chunkSodiumProducer(mode);
                if (GlCompat.SUPPORTS_TERRAIN) {
                    OitPipelines.chunkSodiumProducer(mode, false);
                    OitPipelines.chunkSodiumProducer(mode, true);
                }
            }
        }
    }

    private static List<OitInsertMode> insertModes() {
        OitInsertMode mode = OitConfig.resolveInsertMode();
        return mode == null ? List.of() : List.of(mode);
    }

    private static void warmInsertReplays(List<OitInsertMode> insertModes, boolean sodium) {
        for (OitInsertMode mode : insertModes) {
            RenderPipelineCompiler.feature(ProgramAvailability.insert(mode), () -> {
                OitPipelines.mlabNearestDepth();
                OitPipelines.mlabResolve(mode, false);
                OitPipelines.mlabResolve(mode, true);
                OitPipelines.chunkMlab(mode);
                #if MC >= 26.3
                OitPipelines.chunkMlab(mode, true);
                #endif
                for (BerFamily family : BerFamily.VALUES) OitPipelines.berMlab(family, mode);
                #if MC < 26.3
                FabulousOitPipelines.weatherMlab(mode);
                #endif
                if (sodium) {
                    OitPipelines.chunkSodiumMlab(mode);
                    if (GlCompat.SUPPORTS_TERRAIN) {
                        OitPipelines.chunkSodiumMlab(mode, false);
                        OitPipelines.chunkSodiumMlab(mode, true);
                    }
                }
            });
        }
    }

    private static boolean[] embeddedVariants(Material material) {
        return RenderPassShaders.readsEmbedded(material) ? EMBEDDED_VARIANTS : UNEMBEDDED;
    }

    private static void warmGl() {
        #if MC >= 26.3
        warmNativeResources();
        #endif
        boolean sodium = SodiumClassLoadCheck.PRESENT;
        List<OitInsertMode> insertModes = insertModes();
        if (insertModes.isEmpty()) {
            warmWaveletFullscreen();
            warmWaveletReplays(sodium);
        } else {
            warmInsertReplays(insertModes, sodium);
        }
        if (sodium && GlCompat.SUPPORTS_TERRAIN) {
            RenderPipelineCompiler.feature(ProgramAvailability.Feature.OPAQUE_TERRAIN, () -> {
                TerrainPipelines.solid();
                TerrainPipelines.cutout();
            });
        }

        if (BackendManager.currentBackend() == Backends.INSTANCING) {
            warmInstancing(false);
            #if MC >= 26.3
            NativeOitWarmup.declareSelected();
            #endif
            return;
        }
        IndirectPrograms programs = IndirectPrograms.get();
        programs.warmCulling();
        programs.getOrderProgram();
        List<Material> materials = warmMaterials();
        for (Material material : materials) {
            for (boolean embedded : embeddedVariants(material)) {
                if (OitTransparency.orderIndependent(material)) {
                    if (insertModes.isEmpty()) {
                        OitPipelines.uberProducer(material, OitMode.EVALUATE, embedded, true);
                    }
                    for (OitInsertMode mode : insertModes) {
                        RenderPipelineCompiler.feature(ProgramAvailability.insert(mode),
                                () -> OitPipelines.uberMlab(material, mode, embedded, true));
                    }
                } else {
                    IndirectPipeline.uberPipelineFor(material, embedded, true);
                }
            }
        }
        // Crumbling programs vary by instance type alone; any type's instances may crumble.
        for (InstanceType<?> type : InstanceTypeIds.snapshot().types()) {
            CrumblingPipelines.pipeline(Materials.CRUMBLING, type, true);
        }
        if (GlCompat.SUPPORTS_TERRAIN_MESH
                && Backends.GL_MESH_SHADER_ID.equals(Backend.REGISTRY.getIdOrThrow(BackendManager.currentBackend()))) {
            // Mesh tiers draw every instance type through their own programs.
            MeshVisualDrawManager.warmUp(InstanceTypeIds.snapshot().types(), materials, insertModes.isEmpty());
        }
        #if MC >= 26.3
        NativeOitWarmup.declareSelected();
        #endif
    }

    private static void warmVk() {
        VkPrograms programs = VkPrograms.get();
        if (programs == null) return;
        boolean localRead = programs.localRead();
        List<OitInsertMode> insertModes = insertModes();
        LightSmoothness smoothness = BackendConfig.INSTANCE.lightSmoothness();
        List<Material> materials = warmMaterials();
        List<InstanceType<?>> types = InstanceTypeIds.snapshot().types();
        Map<Material, boolean[]> embeddings = new IdentityHashMap<>();
        for (Material material : materials) embeddings.put(material, embeddedVariants(material));
        VkOitPipelines oit = programs.oit();
        VkUberPipelines uber = programs.uber();
        List<Runnable> jobs = new ArrayList<>();
        jobs.add(() -> {
            if (programs.scatterUploads()) {
                programs.scatterPipeline();
            }
            if (programs.instanceRoute() == VkPrograms.InstanceRoute.INDIRECT) {
                programs.cullPipelines();
                programs.applyPipeline();
                programs.orderPipeline();
            }
            if (programs.hiZRoute() != VkPrograms.HiZRoute.NONE) {
                programs.downsampleFirstPipeline();
                programs.downsampleSecondPipeline();
            }
        });
        #if MC >= 26.3
        RenderPipelineCompiler.warm(() -> {
            warmNativeResources();
            if (insertModes.isEmpty() && !localRead) warmVkReplays();
        });
        #else
        if (insertModes.isEmpty() && !localRead) {
            warmVkReplays();
        }
        #endif
        jobs.add(() -> {
            if (insertModes.isEmpty()) {
                oit.compositePipeline(false);
                oit.compositePipeline(true);
                oit.emissionPipeline();
                oit.oitDepthPipeline();
                if (OitFramebuffer.SPLIT_INSTANCE_BOUNDS) {
                    oit.boundsMergePipeline(false);
                    if (localRead) oit.boundsMergePipeline(true);
                }
            }
            for (OitInsertMode mode : insertModes) {
                oit.mlabNearestDepthPipeline(mode);
            }
            if (insertModes.isEmpty() && localRead) {
                ProgramAvailability.run(ProgramAvailability.Feature.LOCAL_READ, () -> {
                    for (OitMode mode : OitMode.values()) {
                        if (mode == OitMode.OFF) continue;
                        #if MC < 26.3
                        oit.fabulous().layerFoldedPipeline(mode);
                        oit.fabulous().weatherFoldedPipeline(mode);
                        #endif
                        for (BerFamily family : BerFamily.VALUES) oit.berFoldedPipeline(family, mode);
                        oit.chunkFoldedPipeline(mode);
                        #if MC >= 26.3
                        oit.chunkFoldedPipeline(mode, true);
                        #endif
                    }
                });
            }
            for (OitInsertMode mode : insertModes) {
                ProgramAvailability.run(ProgramAvailability.insert(mode), () -> {
                    oit.mlabResolvePipeline(mode);
                    oit.chunkMlabPipeline(mode);
                    #if MC >= 26.3
                    oit.chunkMlabPipeline(mode, true);
                    #endif
                    for (BerFamily family : BerFamily.VALUES) oit.berMlabPipeline(family, mode);
                    #if MC < 26.3
                    oit.fabulous().weatherMlabPipeline(mode);
                    #endif
                });
            }
        });
        if (SodiumClassLoadCheck.PRESENT) jobs.add(() -> {
            VkTerrainPrograms terrain = programs.terrain();
            if (programs.usesGpuTerrain()) {
                ProgramAvailability.run(ProgramAvailability.Feature.OPAQUE_TERRAIN, () -> {
                    terrain.cullPipeline();
                    terrain.drawPipeline(false, VK10.VK_FORMAT_R8G8B8A8_UNORM);
                    terrain.drawPipeline(true, VK10.VK_FORMAT_R8G8B8A8_UNORM);
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
                if (OitTransparency.orderIndependent(material)) continue;
                for (boolean embedded : embeddings.get(material)) {
                    uber.drawPipeline(material, embedded, smoothness, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                            programs.depthFormat());
                }
            }
        });
        if (insertModes.isEmpty()) jobs.add(() -> {
            for (Material material : materials) {
                if (!OitTransparency.orderIndependent(material)) continue;
                for (boolean embedded : embeddings.get(material)) {
                    for (OitMode mode : OitMode.values()) {
                        if (mode == OitMode.OFF) continue;
                        if (localRead) {
                            // Split instance bounds precede the folded passes in a separate floating-point target.
                            if (mode == OitMode.DEPTH_RANGE && OitFramebuffer.SPLIT_INSTANCE_BOUNDS) {
                                uber.oitProducerPipeline(material, embedded, smoothness, mode, false);
                            }
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
                for (boolean embedded : embeddings.get(material)) {
                    for (OitInsertMode mode : insertModes) {
                        ProgramAvailability.run(ProgramAvailability.insert(mode),
                                () -> uber.mlabProducerPipeline(mode, material, embedded, smoothness));
                    }
                }
            }
        });
        jobs.add(() -> {
            for (InstanceType<?> type : types) {
                uber.crumblingPipeline(type, smoothness, VK10.VK_FORMAT_R8G8B8A8_UNORM, programs.depthFormat());
            }
        });
        if (VkCaps.MESH_SHADER_NEGOTIATED && ProgramAvailability.allows(ProgramAvailability.Feature.MESH)
                && Backends.VK_MESH_SHADER_ID.equals(Backend.REGISTRY.getIdOrThrow(
                BackendManager.currentBackend()))) jobs.add(() -> ProgramAvailability.run(
                ProgramAvailability.Feature.MESH, () -> {
                    VkMeshVisualPipelines mesh = programs.meshVisual();
                    mesh.builderPipeline();
                    for (InstanceType<?> type : types) {
                        mesh.crumblingPipeline(type, VK10.VK_FORMAT_R8G8B8A8_UNORM, programs.depthFormat());
                    }
                    for (InstanceType<?> type : types) {
                        for (Material material : materials) {
                            if (!OitTransparency.orderIndependent(material)) {
                                mesh.solidPipeline(type, material, false, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                                        programs.depthFormat());
                            } else {
                                if (insertModes.isEmpty()) {
                                    for (OitMode mode : OitMode.values()) {
                                        if (mode == OitMode.OFF) continue;
                                        if (localRead) {
                                            if (mode == OitMode.DEPTH_RANGE && OitFramebuffer.SPLIT_INSTANCE_BOUNDS) {
                                                mesh.oitPipeline(type, material, false, mode, programs.depthFormat(),
                                                        false);
                                            }
                                            ProgramAvailability.run(ProgramAvailability.Feature.LOCAL_READ,
                                                    () -> mesh.oitPipeline(type, material, false, mode,
                                                            programs.depthFormat(), true));
                                        } else mesh.oitPipeline(type, material, false, mode, programs.depthFormat(),
                                                false);
                                    }
                                }
                                for (OitInsertMode mode : insertModes) {
                                    ProgramAvailability.run(ProgramAvailability.insert(mode),
                                            () -> mesh.mlabPipeline(type, material, false, mode,
                                                    programs.depthFormat()));
                                }
                            }
                        }
                    }
                }));
        for (WarmHook hook : VK_HOOKS) jobs.add(hook::run);
        VkCompilationTasks.run(jobs);
        #if MC >= 26.3
        NativeOitWarmup.declareSelected();
        #endif
    }

    private static void warmVkReplays() {
        for (OitMode mode : OitMode.values()) {
            if (mode == OitMode.OFF) continue;
            OitPipelines.chunkProducer(mode);
            #if MC >= 26.3
            OitPipelines.chunkProducer(mode, true);
            #endif
            for (BerFamily family : BerFamily.VALUES) OitPipelines.berProducer(family, mode);
            #if MC < 26.3
            FabulousOitPipelines.layerProducer(mode);
            FabulousOitPipelines.weatherProducer(mode);
            #endif
        }
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
        if (insertModes.isEmpty()) {
            if (localRead) ProgramAvailability.run(ProgramAvailability.Feature.LOCAL_READ, wavelet);
            else wavelet.run();
        }
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
            RenderPipelineCompiler.clearCache();
        }
    }
}
