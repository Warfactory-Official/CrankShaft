package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.engine_room.flywheel.backend.engine.indirect.VkNativeOitPass;
import dev.engine_room.flywheel.backend.vk.VkContext;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK12;

import java.util.List;

/** Prepares selected native Vulkan variants: fused -> image-free render-pass specs; separate -> pass formats. */
final class NativeVkWarmup {
    // Native water/cloud depth targets.
    private static final int PRODUCER_DEPTH = VK12.VK_FORMAT_D32_SFLOAT;

    private NativeVkWarmup() {
    }

    static void precompileSelected(@Nullable OitInsertMode insert) {
        List<RenderPipeline> declarations = NativeOitPipelines.registeredPipelines();
        int depthFormat = VkContext.mainDepthFormat();
        if (!VkNativeOitPass.fused(VkPrograms.get())) {
            precompileSeparate(declarations, insert, depthFormat);
            return;
        }
        NativeVkEngineWarmup.Snapshot engine = NativeVkEngineWarmup.snapshot();
        for (boolean water : new boolean[]{false, true}) {
            for (boolean clouds : new boolean[]{false, true}) {
                warm(new VkNativeOitPass.Spec(depthFormat, water, clouds, false, insert != null), declarations, insert,
                        engine);
                if (clouds && NativeOitWarmup.supportsCustomClouds()) {
                    warm(new VkNativeOitPass.Spec(depthFormat, water, true, true, insert != null), declarations, insert,
                            engine);
                }
            }
        }
        if (insert == null) {
            VkNativeOitPass emission = VkNativeOitPass.getEmission(depthFormat);
            warm(emission, declarations, null, true, NativeOitWarmup.supportsCustomClouds());
            engine.warm(emission, null);
        }
    }

    private static void warm(VkNativeOitPass.Spec spec, List<RenderPipeline> declarations,
                             @Nullable OitInsertMode insert,
                             NativeVkEngineWarmup.Snapshot engine) {
        VkNativeOitPass pass = VkNativeOitPass.get(spec);
        warm(pass, declarations, insert, false, spec.customClouds());
        engine.warm(pass, insert);
    }

    private static void warm(VkNativeOitPass pass, List<RenderPipeline> declarations,
                             @Nullable OitInsertMode insert, boolean emission, boolean customClouds) {
        for (int i = 0; i < pass.phaseCount(); i++) {
            var phase = pass.phase(i);
            var compatibility = pass.compatibility(i);
            switch (phase) {
                case CULL_BOUNDS -> {
                    utility(RenderPipelines.BLIT_DEPTH_BOUNDS, compatibility);
                    utility(RenderPipelines.OIT_DEPTH_BOUNDS_CULL, compatibility);
                    continue;
                }
                case WATER_MASK -> {
                    utility(RenderPipelines.BLIT_DEPTH, compatibility);
                    utility(RenderPipelines.OIT_WATER_MASK, compatibility);
                    continue;
                }
                case DEPTH_CLOUD -> utility(RenderPipelines.BLIT_DEPTH_DURING_DEPTH_BOUNDS, compatibility);
                default -> {
                }
            }
            if (phase.mode() == OitMode.OFF) throw new IllegalStateException("Native producer warmup has no OIT stage");
            for (RenderPipeline base : declarations) {
                if (NativeOitProducers.stage(base.getShaderDefines()) != phase.mode()
                        || !category(base, phase, customClouds)) {
                    continue;
                }
                if (phase.insert()) {
                    VkNativeOitPipelines.precompileInsert(base, insert, compatibility);
                } else {
                    VkNativeOitPipelines.precompileWavelet(base, phase.mode(), emission, !emission, compatibility);
                }
            }
        }
    }

    private static void precompileSeparate(List<RenderPipeline> declarations, @Nullable OitInsertMode insert,
                                           int mainDepth) {
        // Separate passes keep vanilla's utilities; a capability-recovery cache clear can close them.
        for (RenderPipeline utility : List.of(RenderPipelines.BLIT_DEPTH, RenderPipelines.BLIT_DEPTH_BOUNDS,
                RenderPipelines.OIT_DEPTH_BOUNDS_CULL, RenderPipelines.BLIT_DEPTH_DURING_DEPTH_BOUNDS,
                RenderPipelines.OIT_WATER_MASK)) {
            RenderSystem.getCompiledPipeline(utility);
        }
        for (RenderPipeline base : declarations) {
            OitMode mode = NativeOitProducers.stage(base.getShaderDefines());
            if (mode == null || insert != null && mode == OitMode.GENERATE_COEFFICIENTS) continue;
            if (insert != null && mode == OitMode.DEPTH_RANGE) {
                // Insert: vanilla bounds stage (prepareInsert).
                RenderSystem.getCompiledPipeline(base);
                continue;
            }
            boolean producerDepth = terrain(base) || cloud(base);
            VkNativeOitPipelines.precompileSeparate(base, mode, false, insert, mainDepth);
            if (producerDepth && mainDepth != PRODUCER_DEPTH) {
                VkNativeOitPipelines.precompileSeparate(base, mode, false, insert, PRODUCER_DEPTH);
            }
            if (insert == null && mode == OitMode.EVALUATE) {
                VkNativeOitPipelines.precompileSeparate(base, mode, true, null, mainDepth);
            }
        }
    }

    private static void utility(RenderPipeline pipeline, VkNativePipelineCompiler.Compatibility compatibility) {
        VkNativeOitPipelines.precompileWavelet(pipeline, OitMode.DEPTH_RANGE, false, true, compatibility);
    }

    private static boolean category(RenderPipeline base, VkNativeOitPass.Phase phase, boolean customClouds) {
        boolean terrain = terrain(base);
        boolean cloud = cloud(base);
        return switch (phase) {
            case DEPTH_TERRAIN, COEFFICIENT_TERRAIN, EVALUATE_TERRAIN, INSERT_TERRAIN -> terrain;
            case DEPTH_CLOUD, COEFFICIENT_CLOUD, EVALUATE_CLOUD, INSERT_CLOUD -> cloud;
            case DEPTH_MAIN -> !terrain && !cloud;
            // Custom cloud callbacks use main depth and may reuse the stock cloud declarations.
            case COEFFICIENT_MAIN, EVALUATE_MAIN, INSERT_MAIN, EMISSION_MAIN -> !terrain && (!cloud || customClouds);
            case DEPTH_CUSTOM_CLOUD -> true;
            default -> throw new IllegalArgumentException("Utility phase has no native producer category: " + phase);
        };
    }

    private static boolean terrain(RenderPipeline base) {
        var vertex = base.getShaders().get(ShaderType.VERTEX);
        return vertex.getNamespace().equals("sodium")
                || vertex.getNamespace().equals("minecraft") && vertex.getPath().equals("core/terrain");
    }

    private static boolean cloud(RenderPipeline base) {
        var vertex = base.getShaders().get(ShaderType.VERTEX);
        return vertex.getNamespace().equals("minecraft") && vertex.getPath().equals("core/clouds");
    }
}
