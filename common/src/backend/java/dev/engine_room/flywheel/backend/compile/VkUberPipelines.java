package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.*;
import dev.engine_room.flywheel.backend.MaterialShaderIndices;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.engine.uniform.DebugMode;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderCompiler;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderTransform;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout.*;

/**
 * The uber instance draw pipelines (opaque, the wavelet OIT producers + folded twins, insert producers, crumbling).
 * Type/cutout/fog/embedded are RUNTIME-dispatched, so only light + material shaders + fixed-function state key a pipeline.
 */
public final class VkUberPipelines {
    private final Map<UberDrawKey, VkGraphicsPipeline> drawCache = new HashMap<>();
    private final Map<UberOitKey, VkGraphicsPipeline> oitCache = new HashMap<>();
    private final Map<UberMlabKey, VkGraphicsPipeline> mlabCache = new HashMap<>();
    private final Map<CrumblingKey, VkGraphicsPipeline> crumblingCache = new HashMap<>();

    VkUberPipelines() {
    }

    private static void destroyModules(long... modules) {
        VkDevice device = VkContext.vkDevice();
        for (long module : modules) {
            if (module != 0L) {
                VK12.vkDestroyShaderModule(device, module, null);
            }
        }
    }

    private static List<Binding> drawBindings(boolean embedded, boolean bindless) {
        List<Binding> b = new ArrayList<>();
        int vsfs = STAGE_VERTEX | STAGE_FRAGMENT;
        b.add(new Binding(1, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(2, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(4, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(5, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(6, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(7, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(17, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(18, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(19, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(20, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(21, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(22, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        if (!bindless) {
            // Bindless: all three ride the global table (Sampler0 by draw slot, overlay/lightmap at reserved slots).
            b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
            b.add(new Binding(11, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
            b.add(new Binding(12, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        }
        if (embedded || bindless) {
            b.add(new Binding(23, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        }
        return b;
    }

    private static List<Binding> oitProducerBindings(OitMode mode, boolean embedded, boolean folded, boolean bindless) {
        List<Binding> b = new ArrayList<>();
        int vsfs = STAGE_VERTEX | STAGE_FRAGMENT;
        b.add(new Binding(1, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(2, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(4, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(17, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(21, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        int oitRead = folded ? TYPE_INPUT_ATTACHMENT : TYPE_COMBINED_IMAGE_SAMPLER;
        if (mode != OitMode.DEPTH_RANGE) {
            b.add(new Binding(5, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
            b.add(new Binding(6, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
            b.add(new Binding(18, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
            b.add(new Binding(19, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
            b.add(new Binding(20, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
            b.add(new Binding(22, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
            if (!bindless) {
                b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
                b.add(new Binding(11, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
                b.add(new Binding(12, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
                b.add(new Binding(15, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
            }
            if (folded || !bindless) {
                // Bindless unfolded: reserved slot -- the read rides the global table.
                b.add(new Binding(14, oitRead, STAGE_FRAGMENT));
            }
        }
        if (mode == OitMode.EVALUATE && (folded || !bindless)) {
            for (int i = 24; i <= 27; i++) {
                b.add(new Binding(i, oitRead, STAGE_FRAGMENT));
            }
        }
        if (embedded) {
            b.add(new Binding(7, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        }
        if (embedded || bindless) {
            b.add(new Binding(23, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        }
        return b;
    }

    private static List<Binding> mlabProducerBindings(boolean bindless, OitInsertMode oitMode) {
        List<Binding> b = new ArrayList<>();
        int vsfs = STAGE_VERTEX | STAGE_FRAGMENT;
        b.add(new Binding(1, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(2, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(4, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(17, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(21, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(5, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(6, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(18, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(19, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(20, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(22, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        if (!bindless) {
            b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
            b.add(new Binding(11, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
            b.add(new Binding(12, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        }
        b.add(new Binding(7, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(23, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        VkOitPipelines.mlabBindings(b, oitMode);
        return b;
    }

    private static List<Binding> crumblingBindings() {
        List<Binding> b = new ArrayList<>();
        int vsfs = STAGE_VERTEX | STAGE_FRAGMENT;
        b.add(new Binding(1, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(5, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(6, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(17, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(18, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(19, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(20, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(21, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(22, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        b.add(new Binding(11, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        b.add(new Binding(12, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        b.add(new Binding(13, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        return b;
    }

    public VkGraphicsPipeline drawPipeline(Material material, LightSmoothness smoothness, int colorFormat,
                                           int depthFormat) {
        var key = new UberDrawKey(material.shaders(), material.light(), smoothness, FrameUniforms.debugMode(),
                colorFormat, depthFormat,
                material.transparency(), material.depthTest(), material.writeMask().depth(),
                material.writeMask().color(),
                material.backfaceCulling(), material.polygonOffset(), Generations.current());
        return drawCache.computeIfAbsent(key, this::buildDraw);
    }

    private VkGraphicsPipeline buildDraw(UberDrawKey key) {
        boolean bindless = VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
        Consumer<Compilation> extra = bindless ? VkPrograms.BINDLESS : ShaderAssembly.NO_EXTRA;
        String vsGl = RenderPassShaders.assembleUberIndirectVertex(key.materialShaders(), key.debug() != DebugMode.OFF,
                extra);
        String fsGl = RenderPassShaders.uberFragment(key.light(), key.materialShaders(), key.smoothness(), key.debug(),
                extra);
        long vs = 0;
        long fs = 0;
        VkDescriptorLayout layout = null;
        try {
            vs = VkShaderCompiler.compileModule("indirect_uber_vertex",
                    VkShaderTransform.toVulkan(vsGl, VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
            fs = VkShaderCompiler.compileModule(
                    "flw_indirect_uber" + (key.debug() == DebugMode.OFF ? "" : "_debug_" + key.debug()
                                                                                              .getSerializedName()),
                    VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
            var config = VkGraphicsPipeline.material(key.colorFormat(), key.depthFormat(), key.transparency(),
                    key.depthTest(),
                    key.depthWrite(), key.colorWrite(), key.cull(), key.polygonOffset());
            layout = new VkDescriptorLayout(drawBindings(true, bindless), 0, 0, bindless);
            return new VkGraphicsPipeline(layout, vs, fs, config);
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModules(vs, fs);
            throw t;
        }
    }

    public VkGraphicsPipeline oitProducerPipeline(Material material, LightSmoothness smoothness, OitMode mode,
                                                  boolean folded) {
        var key = new UberOitKey(material.shaders(), material.light(), smoothness, FrameUniforms.debugMode(), mode,
                folded,
                material.depthTest(), material.backfaceCulling(), material.polygonOffset(), Generations.current());
        return oitCache.computeIfAbsent(key, this::buildOitProducer);
    }

    private VkGraphicsPipeline buildOitProducer(UberOitKey key) {
        boolean bindless = VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
        Consumer<Compilation> extra = bindless ? VkPrograms.BINDLESS : ShaderAssembly.NO_EXTRA;
        String vsGl = RenderPassShaders.assembleUberIndirectVertex(key.materialShaders(), key.debug() != DebugMode.OFF,
                extra);
        String fsGl = RenderPassShaders.assembleUberOitFragment(key.mode(), key.light(), key.materialShaders(),
                key.smoothness(), key.debug(),
                key.folded() ? extra.andThen(VkPrograms.LOCAL_READ) : extra);
        long vs = 0;
        long fs = 0;
        VkDescriptorLayout layout = null;
        try {
            vs = VkShaderCompiler.compileModule("oit_uber_vertex",
                    VkShaderTransform.toVulkan(vsGl, VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
            fs = VkShaderCompiler.compileModule(
                    "oit_uber_" + key.mode() + (key.debug() == DebugMode.OFF ? "" : "_debug_" + key.debug()
                                                                                                   .getSerializedName()),
                    VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
            // GL OitPipelines.uberProducer parity: producer offsets keep the slope term (constant 10, slope 1);
            // the entity-shadow decal z-fights at grazing without it.
            VkGraphicsPipeline.Config base = key.folded() ? VkOitPipelines.foldedProducerConfig(
                    key.mode()) : VkOitPipelines.oitProducerConfig(key.mode());
            var config = new VkGraphicsPipeline.Config(base.colorFormats(), base.blends(), base.depthTest(),
                    base.depthWrite(),
                    VkGraphicsPipeline.compareOp(key.depthTest()), base.vertex(),
                    key.cull() ? VK12.VK_CULL_MODE_BACK_BIT : VK12.VK_CULL_MODE_NONE, base.depthFormat(),
                    key.polygonOffset() ? 10.0F : 0.0F, key.polygonOffset() ? 1.0F : 0.0F,
                    base.attachmentLocations(), base.inputAttachmentIndices());
            layout = new VkDescriptorLayout(oitProducerBindings(key.mode(), true, key.folded(), bindless), 0, 0,
                    bindless);
            return new VkGraphicsPipeline(layout, vs, fs, config);
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModules(vs, fs);
            throw t;
        }
    }

    public VkGraphicsPipeline mlabProducerPipeline(OitInsertMode oitMode, Material material,
                                                   LightSmoothness smoothness) {
        var key = new UberMlabKey(oitMode, material.shaders(), material.light(), smoothness, FrameUniforms.debugMode(),
                material.depthTest(), material.backfaceCulling(), material.polygonOffset(), Generations.current());
        return mlabCache.computeIfAbsent(key, this::buildMlabProducer);
    }

    private VkGraphicsPipeline buildMlabProducer(UberMlabKey key) {
        boolean bindless = VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
        Consumer<Compilation> extra = bindless ? VkPrograms.BINDLESS : ShaderAssembly.NO_EXTRA;
        String vsGl = RenderPassShaders.assembleUberIndirectVertex(key.materialShaders(), key.debug() != DebugMode.OFF,
                extra);
        String fsGl = RenderPassShaders.assembleUberMlabFragment(key.oitMode(), key.light(), key.materialShaders(),
                key.smoothness(), key.debug(), extra);
        long vs = 0;
        long fs = 0;
        VkDescriptorLayout layout = null;
        try {
            vs = VkShaderCompiler.compileModule("mlab_uber_vertex",
                    VkShaderTransform.toVulkan(vsGl, VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
            fs = VkShaderCompiler.compileModule(
                    "mlab_uber_" + key.oitMode() + (key.debug() == DebugMode.OFF ? "" : "_debug_" + key.debug()
                                                                                                       .getSerializedName()),
                    VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
            var config = new VkGraphicsPipeline.Config(VkOitPipelines.MLAB_NO_COLOR, VkOitPipelines.MLAB_NO_BLEND, true,
                    false,
                    VkGraphicsPipeline.compareOp(key.depthTest()), VkGraphicsPipeline.Vertex.INTERNAL,
                    key.cull() ? VK12.VK_CULL_MODE_BACK_BIT : VK12.VK_CULL_MODE_NONE, VkOitPipelines.FMT_D32,
                    key.polygonOffset() ? 10.0F : 0.0F, key.polygonOffset() ? 1.0F : 0.0F);
            layout = new VkDescriptorLayout(mlabProducerBindings(bindless, key.oitMode()), 0, 0, bindless);
            return new VkGraphicsPipeline(layout, vs, fs, config);
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModules(vs, fs);
            throw t;
        }
    }

    public VkGraphicsPipeline crumblingPipeline(InstanceType<?> type, LightSmoothness smoothness, int colorFormat,
                                                int depthFormat) {
        return crumblingCache.computeIfAbsent(
                new CrumblingKey(type, smoothness, FrameUniforms.debugMode(), colorFormat, depthFormat),
                this::buildCrumbling);
    }

    private VkGraphicsPipeline buildCrumbling(CrumblingKey key) {
        // _FLW_CRUMBLING drops the cull index table (binding 2) and reads the object id from gl_InstanceIndex; material shaders are DEFAULT.
        String vsGl = RenderPassShaders.assembleIndirectCrumblingVertex(key.instanceType(),
                key.debug() != DebugMode.OFF);
        String fsGl = RenderPassShaders.crumblingFragment(true, key.smoothness(), key.debug());
        long vs = 0;
        long fs = 0;
        VkDescriptorLayout layout = null;
        try {
            vs = VkShaderCompiler.compileModule("crumbling_vertex",
                    VkShaderTransform.toVulkan(vsGl, VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
            fs = VkShaderCompiler.compileModule("crumbling_frag",
                    VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
            // Reversed-Z GREATER_OR_EQUAL + BACK cull mirror the opaque path; no depth write (overlay), the crumbling
            // blend, + polygon offset (constant 10, slope 1 -- GL parity) so the overlay wins the depth test.
            var config = new VkGraphicsPipeline.Config(new int[]{key.colorFormat()},
                    new VkGraphicsPipeline.Blend[]{VkGraphicsPipeline.crumbling()},
                    true, false, VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, VkGraphicsPipeline.Vertex.INTERNAL,
                    VK12.VK_CULL_MODE_BACK_BIT, key.depthFormat(), 10.0F, 1.0F);
            layout = new VkDescriptorLayout(crumblingBindings(), 0, 0);
            return new VkGraphicsPipeline(layout, vs, fs, config);
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModules(vs, fs);
            throw t;
        }
    }

    void delete() {
        drawCache.values().forEach(VkGraphicsPipeline::delete);
        drawCache.clear();
        oitCache.values().forEach(VkGraphicsPipeline::delete);
        oitCache.clear();
        mlabCache.values().forEach(VkGraphicsPipeline::delete);
        mlabCache.clear();
        crumblingCache.values().forEach(VkGraphicsPipeline::delete);
        crumblingCache.clear();
    }

    private record Generations(int types, int cutouts, int fogs) {
        static Generations current() {
            return new Generations(InstanceTypeIds.snapshot().types().size(),
                    MaterialShaderIndices.cutoutSources().all().size(),
                    MaterialShaderIndices.fogSources().all().size());
        }
    }

    private record UberDrawKey(MaterialShaders materialShaders, LightShader light, LightSmoothness smoothness,
                               DebugMode debug, int colorFormat, int depthFormat, Transparency transparency,
                               DepthTest depthTest,
                               boolean depthWrite, boolean colorWrite, boolean cull, boolean polygonOffset,
                               Generations generations) {
    }

    private record UberOitKey(MaterialShaders materialShaders, LightShader light, LightSmoothness smoothness,
                              DebugMode debug, OitMode mode, boolean folded, DepthTest depthTest, boolean cull,
                              boolean polygonOffset,
                              Generations generations) {
    }

    private record UberMlabKey(OitInsertMode oitMode, MaterialShaders materialShaders, LightShader light,
                               LightSmoothness smoothness,
                               DebugMode debug, DepthTest depthTest, boolean cull, boolean polygonOffset,
                               Generations generations) {
    }

    private record CrumblingKey(InstanceType<?> instanceType, LightSmoothness smoothness, DebugMode debug,
                                int colorFormat, int depthFormat) {
    }
}
