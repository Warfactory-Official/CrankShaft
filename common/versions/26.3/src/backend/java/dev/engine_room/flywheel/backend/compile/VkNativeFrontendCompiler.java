package dev.engine_room.flywheel.backend.compile;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.VkPipelineCaches;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Publishes exact-format Vulkan pipelines through the native frontend while retaining storage/input descriptors.
 * Returned pipelines own their GPU handles through Renderpearl's deferred destruction; the compiler owns only
 * CPU compilation state. Owners close pipelines and join compilation jobs before closing the compiler.
 */
public final class VkNativeFrontendCompiler implements AutoCloseable {
    private static BackendFactory backendFactory;
    private final VkNativePipelineCompiler stages = new VkNativePipelineCompiler();

    /** Loader initialization, before any loading/configuration pipeline compilation begins. */
    public static void registerBackendFactory(BackendFactory factory) {
        backendFactory = factory;
    }

    /** Loader-specific ABI and stencil state; invoked only while compiling a new GPU pipeline. */
    public interface BackendFactory {
        VulkanRenderPipeline create(VulkanDevice device, long withDepthStencil, long withDepth, long withoutDepth,
                                    long layout, long descriptorSetLayout, LongList modules,
                                    List<BindGroupLayout.UniformDescription> uniforms);

        void configureStencil(DepthStencilState nativeState, VkPipelineDepthStencilStateCreateInfo target);
    }

    public CompiledPipeline compile(RenderPipeline base, ShaderSource source,
                                    Map<String, VkNativePipelineCompiler.EngineBinding> engineBindings,
                                    VkNativePipelineCompiler.Compatibility compatibility) {
        boolean depthless = compatibility.depthFormat() == VK12.VK_FORMAT_UNDEFINED;
        if (depthless && (compatibility.renderPass() != 0 || base.getDepthStencilState() != null)) {
            throw new IllegalArgumentException(
                    "Native Vulkan producer compatibility requires its actual depth attachment format");
        }
        if (compatibility.renderPass() == 0 && compatibility.subpass() != 0) {
            throw new IllegalArgumentException("Dynamic rendering has no numbered subpasses");
        }
        VkDescriptorLayout layout = null;
        long pipeline = 0;
        LongArrayList modules = new LongArrayList();
        try (var compiled = stages.compile(base, source, engineBindings);
             MemoryStack stack = MemoryStack.stackPush()) {
            // The native VulkanRenderPass pushes VK_SHADER_STAGE_ALL; retain its full payload contract.
            int pushStages = compiled.pushConstantSize() == 0 ? 0 : VK12.VK_SHADER_STAGE_ALL;
            layout = new VkDescriptorLayout(compiled.descriptorBindings(), compiled.pushConstantSize(), pushStages,
                    false, false);
            var pointer = stack.callocLong(1);
            var shaderStages = VkPipelineShaderStageCreateInfo.calloc(compiled.shaders().size(), stack);
            int shaderIndex = 0;
            for (var shader : compiled.shaders().entrySet()) {
                var moduleInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(shader.getValue().spv());
                VkCaps.checkPipelineResult(VK12.vkCreateShaderModule(VkContext.vkDevice(), moduleInfo, null, pointer),
                        "native producer shader module");
                long module = pointer.get(0);
                modules.add(module);
                shaderStages.get(shaderIndex++).sType$Default().stage(VulkanConst.toVk(shader.getKey()))
                        .module(module).pName(stack.UTF8("main"));
            }
            var vertexBindings = VkVertexInputBindingDescription.calloc(compiled.vertexBuffers().size(), stack);
            var divisors = VkVertexInputBindingDivisorDescriptionEXT.calloc(compiled.vertexBuffers().size(), stack);
            int divisorCount = 0;
            for (int i = 0; i < vertexBindings.capacity(); i++) {
                var vertex = compiled.vertexBuffers().get(i);
                vertexBindings.get(i).binding(vertex.bufferSlot()).stride(vertex.stride())
                        .inputRate(vertex.stepRate() > 0 ? VK12.VK_VERTEX_INPUT_RATE_INSTANCE
                        : VK12.VK_VERTEX_INPUT_RATE_VERTEX);
                if (vertex.stepRate() > 0) {
                    divisors.get(divisorCount++).binding(vertex.bufferSlot()).divisor(vertex.stepRate());
                }
            }
            divisors.limit(divisorCount);
            var attributes = VkVertexInputAttributeDescription.calloc(compiled.attributes().size(), stack);
            for (int i = 0; i < attributes.capacity(); i++) {
                var attribute = compiled.attributes().get(i);
                attributes.get(i).location(attribute.location()).binding(attribute.bufferSlot())
                        .offset(attribute.offset())
                        .format(VulkanConst.toVk(attribute.format()));
            }
            var vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                    .pVertexBindingDescriptions(vertexBindings).pVertexAttributeDescriptions(attributes);
            if (divisorCount != 0) vertexInput.pNext(VkPipelineVertexInputDivisorStateCreateInfoEXT.calloc(stack)
                    .sType$Default().pVertexBindingDivisors(divisors).address());
            VkCaps.requireVertexInput(vertexInput);
            var assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(VulkanConst.toVk(base.getPrimitiveTopology()));
            var raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(VulkanConst.toVk(base.getPolygonMode()))
                    .cullMode(base.isCull() ? VK12.VK_CULL_MODE_BACK_BIT : 0)
                    .frontFace(VK12.VK_FRONT_FACE_CLOCKWISE).lineWidth(1.0f);
            var depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default();
            var nativeDepth = base.getDepthStencilState();
            if (nativeDepth != null) {
                raster.depthBiasEnable(nativeDepth.depthBiasConstant() != 0 || nativeDepth.depthBiasScaleFactor() != 0)
                        .depthBiasConstantFactor(nativeDepth.depthBiasConstant())
                        .depthBiasSlopeFactor(nativeDepth.depthBiasScaleFactor());
                depth.depthTestEnable(true).depthWriteEnable(nativeDepth.writeDepth())
                        .depthCompareOp(VulkanConst.toVk(nativeDepth.depthTest()));
                backendFactory.configureStencil(nativeDepth, depth);
            }
            List<ColorTargetState> colors = compatibility.colorTargets();
            int[] formats = new int[colors.size()];
            VkGraphicsPipeline.Blend[] blends = new VkGraphicsPipeline.Blend[colors.size()];
            for (int i = 0; i < colors.size(); i++) {
                formats[i] = colors.get(i) == null ? VK12.VK_FORMAT_UNDEFINED
                        : VulkanConst.toVk(colors.get(i).format());
                blends[i] = blend(colors.get(i));
            }
            VkGraphicsPipeline.requireAttachments(formats, blends, compatibility.depthFormat());
            var create = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            create.get(0).sType$Default().pStages(shaderStages).pVertexInputState(vertexInput)
                    .pInputAssemblyState(assembly)
                    .pRasterizationState(raster).pDepthStencilState(depth)
                    .pColorBlendState(VkGraphicsPipeline.colorBlendState(stack, blends))
                    .pViewportState(VkGraphicsPipeline.viewportState(stack))
                    .pMultisampleState(VkGraphicsPipeline.multisampleState(stack))
                    .pDynamicState(VkGraphicsPipeline.dynamicState(stack)).layout(layout.pipelineLayout());
            if (compatibility.renderPass() != 0) {
                create.get(0).renderPass(compatibility.renderPass()).subpass(compatibility.subpass());
            } else {
                create.get(0)
                        .pNext(VkGraphicsPipeline.renderingInfo(stack, formats, compatibility.depthFormat()).address());
            }
            pointer.put(0, 0);
            int result = VK12.vkCreateGraphicsPipelines(VkContext.vkDevice(), VkPipelineCaches.handle(), create, null,
                    pointer);
            pipeline = pointer.get(0);
            VkCaps.checkPipelineResult(result, "native Vulkan producer pipeline " + base.getLocation());
            long stencilPipeline = VkContext.stencilFormat(compatibility.depthFormat()) == 0 ? 0 : pipeline;
            // One exact-depth handle also serves the native stencil selector. The native destructor frees
            // withDepthPipeline, so this alias avoids a second format variant and its separate ownership.
            var backend = backendFactory.create(VkContext.device(), stencilPipeline,
                    depthless ? 0 : pipeline, depthless ? pipeline : 0, layout.pipelineLayout(), layout.setLayout(),
                    modules, compiled.nativeUniforms());
            var frontend = new FrontendRenderPipeline(base.getLocation().toString(), backend,
                    Collections.unmodifiableList(new ArrayList<>(base.getVertexFormatBindings())),
                    compiled.nativeUniformIndices(),
                    compiled.nativeUniforms(), compatibility.colorTargets(), base.wantsDepthTexture(),
                    compiled.pushConstantSize());
            return new CompiledPipeline(frontend, backend, layout.pipelineLayout(), compiled.engineResources());
        } catch (RuntimeException | Error failure) {
            if (pipeline != 0) VK12.vkDestroyPipeline(VkContext.vkDevice(), pipeline, null);
            for (long module : modules) VK12.vkDestroyShaderModule(VkContext.vkDevice(), module, null);
            if (layout != null) layout.delete();
            throw failure;
        }
    }

    private static VkGraphicsPipeline.Blend blend(@Nullable ColorTargetState color) {
        if (color == null) return VkGraphicsPipeline.noColorWrite();
        int mask = VulkanConst.toVk(color);
        if (color.blendFunction().isEmpty()) return new VkGraphicsPipeline.Blend(false, 0, 0, 0, 0, 0, 0, mask);
        var function = color.blendFunction().get();
        return new VkGraphicsPipeline.Blend(true, VulkanConst.toVk(function.color().sourceFactor()),
                VulkanConst.toVk(function.color().destFactor()), VulkanConst.toVk(function.color().op()),
                VulkanConst.toVk(function.alpha().sourceFactor()), VulkanConst.toVk(function.alpha().destFactor()),
                VulkanConst.toVk(function.alpha().op()), mask);
    }

    @Override
    public void close() {
        stages.close();
    }

    public record CompiledPipeline(FrontendRenderPipeline frontend, VulkanRenderPipeline backend, long pipelineLayout,
                                   List<VkNativePipelineCompiler.EngineResource> engineResources)
            implements AutoCloseable {
        @Override
        public void close() {
            frontend.close();
        }
    }
}
