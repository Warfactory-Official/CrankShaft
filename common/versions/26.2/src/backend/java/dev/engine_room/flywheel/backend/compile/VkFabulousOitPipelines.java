package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderCompiler;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderTransform;
import org.lwjgl.vulkan.VK12;

import java.util.ArrayList;
import java.util.List;

import static dev.engine_room.flywheel.backend.compile.VkOitPipelines.*;
import static dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout.*;

/**
 * Vulkan twin of {@code FabulousOitPipelines}: resolved-layer and captured-weather producers.
 */
public final class VkFabulousOitPipelines {
    private final VkGraphicsPipeline[] layerFolded = new VkGraphicsPipeline[OitMode.values().length];
    private final VkGraphicsPipeline[] weatherFolded = new VkGraphicsPipeline[OitMode.values().length];
    private final VkGraphicsPipeline[] weatherMlab = new VkGraphicsPipeline[OitInsertMode.values().length];
    private final int depthFormat;

    VkFabulousOitPipelines(int depthFormat) {
        this.depthFormat = depthFormat;
    }

    private static List<Binding> layerFoldedBindings(OitMode mode) {
        List<Binding> b = new ArrayList<>();
        b.add(new Binding(29, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        b.add(new Binding(30, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        foldedExtraBindings(b, mode);
        return b;
    }

    private static List<Binding> weatherFoldedBindings(OitMode mode) {
        List<Binding> b = weatherBindings();
        foldedExtraBindings(b, mode);
        return b;
    }

    private static List<Binding> weatherBindings() {
        List<Binding> b = new ArrayList<>();
        int vsfs = STAGE_VERTEX | STAGE_FRAGMENT;
        b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        b.add(new Binding(12, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_VERTEX));
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(17, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(18, TYPE_UNIFORM_BUFFER, vsfs));
        return b;
    }

    public VkGraphicsPipeline layerFoldedPipeline(OitMode mode) {
        VkGraphicsPipeline p = layerFolded[mode.ordinal()];
        if (p == null) {
            String fsGl = RenderPassShaders.assembleLayerOitFragment(mode, VkPrograms.LOCAL_READ);
            long vs = 0;
            long fs = 0;
            VkDescriptorLayout layout = null;
            try {
                vs = VkShaderCompiler.compileModule("layer_fullscreen",
                        VkShaderTransform.toVulkan(RenderPassShaders.fullscreenVertex(),
                                VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
                fs = VkShaderCompiler.compileModule("layer_oit" + mode.name,
                        VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT),
                        VkShaderCompiler.KIND_FRAGMENT);
                VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(FOLDED_FORMATS, foldedBlends(mode),
                        true, false,
                        VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, VkGraphicsPipeline.Vertex.NONE, VK12.VK_CULL_MODE_NONE,
                        depthFormat)
                        .withLocalRead(foldedLocations(mode), FOLDED_INPUT_INDICES);
                layout = new VkDescriptorLayout(layerFoldedBindings(mode), 0, 0);
                p = new VkGraphicsPipeline(layout, vs, fs, config);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(vs, fs);
                if (t instanceof BackendUnavailableException failure) {
                    throw VkPrograms.tagged(ProgramAvailability.Feature.LOCAL_READ, failure);
                }
                throw t;
            }
            layerFolded[mode.ordinal()] = p;
        }
        return p;
    }

    public VkGraphicsPipeline weatherFoldedPipeline(OitMode mode) {
        VkGraphicsPipeline p = weatherFolded[mode.ordinal()];
        if (p == null) {
            String fsGl = RenderPassShaders.assembleWeatherOitFragment(mode, VkPrograms.LOCAL_READ);
            long vs = 0;
            long fs = 0;
            VkDescriptorLayout layout = null;
            try {
                vs = VkShaderCompiler.compileModule("weather_oit_vertex",
                        VkShaderTransform.toVulkan(RenderPassShaders.assembleWeatherOitVertex(),
                                VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
                fs = VkShaderCompiler.compileModule("weather_oit" + mode.name,
                        VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT),
                        VkShaderCompiler.KIND_FRAGMENT);
                VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(FOLDED_FORMATS, foldedBlends(mode),
                        true, false,
                        VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, VkGraphicsPipeline.Vertex.PARTICLE, VK12.VK_CULL_MODE_NONE,
                        depthFormat)
                        .withLocalRead(foldedLocations(mode), FOLDED_INPUT_INDICES);
                layout = new VkDescriptorLayout(weatherFoldedBindings(mode), 0, 0);
                p = new VkGraphicsPipeline(layout, vs, fs, config);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(vs, fs);
                if (t instanceof BackendUnavailableException failure) {
                    throw VkPrograms.tagged(ProgramAvailability.Feature.LOCAL_READ, failure);
                }
                throw t;
            }
            weatherFolded[mode.ordinal()] = p;
        }
        return p;
    }

    public VkGraphicsPipeline weatherMlabPipeline(OitInsertMode oitMode) {
        if (weatherMlab[oitMode.ordinal()] == null) {
            long vs = 0;
            long fs = 0;
            VkDescriptorLayout layout = null;
            try {
                vs = VkShaderCompiler.compileModule("weather_oit_vertex",
                        VkShaderTransform.toVulkan(RenderPassShaders.assembleWeatherOitVertex(),
                                VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
                fs = VkShaderCompiler.compileModule("weather_mlab_" + oitMode,
                        VkShaderTransform.toVulkan(RenderPassShaders.assembleWeatherMlabFragment(oitMode),
                                VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
                VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(MLAB_NO_COLOR, MLAB_NO_BLEND, true,
                        false,
                        VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, VkGraphicsPipeline.Vertex.PARTICLE, VK12.VK_CULL_MODE_NONE,
                        depthFormat);
                List<Binding> b = weatherBindings();
                mlabBindings(b, oitMode);
                layout = new VkDescriptorLayout(b, 0, 0);
                weatherMlab[oitMode.ordinal()] = new VkGraphicsPipeline(layout, vs, fs, config);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(vs, fs);
                if (t instanceof BackendUnavailableException failure) {
                    throw VkPrograms.tagged(ProgramAvailability.insert(oitMode), failure);
                }
                throw t;
            }
        }
        return weatherMlab[oitMode.ordinal()];
    }

    void delete() {
        deleteAll(layerFolded);
        deleteAll(weatherFolded);
        deleteAll(weatherMlab);
    }

    private static void deleteAll(VkGraphicsPipeline[] pipelines) {
        for (int i = 0; i < pipelines.length; i++) {
            if (pipelines[i] != null) {
                pipelines[i].delete();
                pipelines[i] = null;
            }
        }
    }
}
