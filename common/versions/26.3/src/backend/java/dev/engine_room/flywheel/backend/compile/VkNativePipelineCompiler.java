package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.util.ShaderCompileException;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import org.lwjgl.vulkan.VK12;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compiles native vertex/fragment stages without dropping the engine's storage buffers or input attachments.
 * Native uniforms occupy the ordinary frontend indices; engine descriptors use sparse bindings in the same set.
 * The owner joins compilation jobs before closing this compiler or the returned source modules.
 */
public final class VkNativePipelineCompiler implements AutoCloseable {
    public static final int ENGINE_BINDING_BASE = 64;
    // Renderpearl's ordinary reflection omits subpass inputs.
    private static final int[] RESOURCE_TYPES = {Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,
            Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, Spvc.SPVC_RESOURCE_TYPE_SUBPASS_INPUT,
            Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,
            Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS};
    private final GlslCompiler compiler;

    public VkNativePipelineCompiler() {
        var info = RenderSystem.getDevice().getDeviceInfo();
        compiler = new GlslCompiler(info.isZZeroToOne(), info.features().shaderDrawParameters());
    }

    public CompiledSources compile(RenderPipeline pipeline, ShaderSource source,
            Map<String, EngineBinding> engineBindings) {
        EnumMap<ShaderType, SpvModule> modules = new EnumMap<>(ShaderType.class);
        try {
            for (ShaderType stage : ShaderType.values()) {
                var id = pipeline.getShaders().get(stage);
                if (id == null) continue;
                modules.put(stage, compiler.compileToSpv(id.toString(), source.getShader(id, stage), stage,
                        pipeline.getShaderDefines(), source));
            }
            var nativeDeclarations = new HashMap<String, BindGroupLayout.UniformDescription>();
            for (var uniform : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) {
                nativeDeclarations.put(uniform.name(), uniform);
            }
            DescriptorState state = new DescriptorState(nativeDeclarations, engineBindings);
            for (var shader : modules.entrySet()) state.remap(shader.getValue(), stageFlags(shader.getKey()));

            List<BackendRenderPipeline.CreateInfo.VertexBuffer> vertexBuffers = new ArrayList<>();
            List<BackendRenderPipeline.CreateInfo.AttribBinding> attributes = new ArrayList<>();
            SpvModule vertex = modules.get(ShaderType.VERTEX);
            if (vertex != null) vertexInputs(pipeline, vertex, vertexBuffers, attributes);
            SpvModule fragment = modules.get(ShaderType.FRAGMENT);
            if (vertex != null && fragment != null) validateInterfaces(vertex, fragment);

            for (SpvModule module : modules.values()) {
                var pushes = module.reflect().pushConstants();
                if (pushes.size() > 1) throw unsupported("Multiple native push-constant blocks", pipeline);
                if (!pushes.isEmpty() && pushes.getFirst().size() > pipeline.pushConstantSize()) {
                    throw unsupported("Native push-constant block exceeds its declared payload", pipeline);
                }
            }
            return new CompiledSources(Map.copyOf(modules), List.copyOf(vertexBuffers), List.copyOf(attributes),
                    Object2IntMaps.unmodifiable(state.nativeIndices), List.copyOf(state.nativeUniforms),
                    List.copyOf(state.bindings.values()), List.copyOf(state.engineResources.values()),
                    pipeline.pushConstantSize());
        } catch (ShaderCompileException failure) {
            modules.values().forEach(SpvModule::close);
            throw new BackendUnavailableException("Could not compile native Vulkan producer " + pipeline, failure);
        } catch (RuntimeException | Error failure) {
            modules.values().forEach(SpvModule::close);
            throw failure;
        }
    }

    private static BackendUnavailableException unsupported(String message, RenderPipeline pipeline) {
        return new BackendUnavailableException(message + ": " + pipeline);
    }

    private static int stageFlags(ShaderType stage) {
        return stage == ShaderType.VERTEX ? VK12.VK_SHADER_STAGE_VERTEX_BIT : VK12.VK_SHADER_STAGE_FRAGMENT_BIT;
    }

    private static void vertexInputs(RenderPipeline pipeline, SpvModule vertex,
                                     List<BackendRenderPipeline.CreateInfo.VertexBuffer> buffers,
                                     List<BackendRenderPipeline.CreateInfo.AttribBinding> attributes)
            throws ShaderCompileException {
        Map<String, SpvModule.Reflection.InterfaceVariable> inputs = new HashMap<>();
        for (var input : vertex.reflect().inputs()) inputs.put(input.name(), input);
        Map<Integer, GpuFormat> formats = new HashMap<>();
        String previousName = null;
        int previousLocation = -1;
        List<VertexFormat> vertexFormats = pipeline.getVertexFormatBindings();
        for (int slot = 0; slot < vertexFormats.size(); slot++) {
            VertexFormat format = vertexFormats.get(slot);
            if (format == null) continue;
            buffers.add(new BackendRenderPipeline.CreateInfo.VertexBuffer(slot, format.getVertexSize(),
                    format.getStepRate()));
            for (var element : format.getElements()) {
                var input = inputs.get(element.name());
                if (input == null) continue;
                int location = element.name().equals(previousName) ? previousLocation + 1 : input.location();
                attributes.add(new BackendRenderPipeline.CreateInfo.AttribBinding(slot, location, element.offset(),
                        element.format()));
                formats.put(location, element.format());
                previousName = element.name();
                previousLocation = location;
            }
        }
        for (var input : inputs.values()) {
            if (input.decoration(Spv.SpvDecorationComponent) != 0) {
                throw unsupported("Component-qualified native vertex input", pipeline);
            }
            GpuFormat format = formats.get(input.location());
            if (format == null) {
                throw unsupported("Native vertex input has no matching format: " + input.name(), pipeline);
            }
            int expectedBase = switch (format.componentType()) {
                case UNORM_8, SNORM_8, UNORM_16, SNORM_16, FLOAT_16, FLOAT_32 -> Spvc.SPVC_BASETYPE_FP32;
                case UINT_8, UINT_16, UINT_32 -> Spvc.SPVC_BASETYPE_UINT32;
                case SINT_8, SINT_16, SINT_32 -> Spvc.SPVC_BASETYPE_INT32;
                default -> throw unsupported("Unsupported native vertex format: " + format, pipeline);
            };
            if (input.type().baseType() != expectedBase || input.type().vectorSize() > format.componentCount()) {
                throw unsupported("Native vertex format does not match shader input: " + input.name(), pipeline);
            }
        }
    }

    private static void validateInterfaces(SpvModule vertex, SpvModule fragment) throws ShaderCompileException {
        var outputs = interfaceSlots(vertex.reflect().outputs());
        for (var input : interfaceSlots(fragment.reflect().inputs()).entrySet()) {
            if (!input.getValue().equals(outputs.get(input.getKey()))) {
                throw new BackendUnavailableException("Native Vulkan stage interface mismatch at location "
                        + input.getKey());
            }
        }
    }

    private static Map<Integer, InterfaceSlot> interfaceSlots(List<SpvModule.Reflection.InterfaceVariable> variables) {
        Map<Integer, InterfaceSlot> slots = new HashMap<>();
        for (var variable : variables) {
            if (variable.decoration(Spv.SpvDecorationComponent) != 0) {
                throw new BackendUnavailableException("Component-qualified native stage interface: " + variable.name());
            }
            var type = variable.type();
            if (type.baseType() == Spvc.SPVC_BASETYPE_INT64 || type.baseType() == Spvc.SPVC_BASETYPE_UINT64
                    || type.baseType() == Spvc.SPVC_BASETYPE_FP64 || type.baseType() == Spvc.SPVC_BASETYPE_STRUCT) {
                throw new BackendUnavailableException("Unsupported native stage interface type: " + variable.name());
            }
            int count = 1;
            for (int i = 0; i < type.arrayDimensions(); i++) count = Math.multiplyExact(count, type.arrayLength(i));
            var slot = new InterfaceSlot(type.baseType(), type.vectorSize(),
                    variable.hasDecoration(Spv.SpvDecorationFlat));
            for (int i = 0; i < count; i++) slots.put(variable.location() + i, slot);
        }
        return slots;
    }

    private record InterfaceSlot(int baseType, int components, boolean flat) {
    }

    private static final class DescriptorState {
        private final Map<String, BindGroupLayout.UniformDescription> declarations;
        private final Map<String, EngineBinding> requestedEngineBindings;
        private final Object2IntOpenHashMap<String> nativeIndices = new Object2IntOpenHashMap<>();
        private final List<BindGroupLayout.UniformDescription> nativeUniforms = new ArrayList<>();
        private final Map<String, DescriptorShape> shapes = new HashMap<>();
        private final TreeMap<Integer, VkDescriptorLayout.Binding> bindings = new TreeMap<>();
        private final TreeMap<Integer, EngineResource> engineResources = new TreeMap<>();

        private DescriptorState(Map<String, BindGroupLayout.UniformDescription> declarations,
                                Map<String, EngineBinding> engineBindings) {
            this.declarations = declarations;
            requestedEngineBindings = Map.copyOf(engineBindings);
            nativeIndices.defaultReturnValue(-1);
        }

        private void remap(SpvModule module, int stage) {
            long context = 0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pointer = stack.callocPointer(1);
                check(Spvc.spvc_context_create(pointer));
                context = pointer.get(0);
                IntBuffer words = module.spv().asIntBuffer();
                check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), pointer));
                long ir = pointer.get(0);
                check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, ir,
                        Spvc.SPVC_CAPTURE_MODE_COPY, pointer));
                long reflection = pointer.get(0);
                check(Spvc.spvc_compiler_create_shader_resources(reflection, pointer));
                long resources = pointer.get(0);
                PointerBuffer count = stack.callocPointer(1);
                IntBuffer decorationOffset = stack.callocInt(1);
                for (int resourceType : RESOURCE_TYPES) {
                    check(Spvc.spvc_resources_get_resource_list_for_type(resources, resourceType, pointer, count));
                    var reflected = SpvcReflectedResource.create(pointer.get(0), Math.toIntExact(count.get(0)));
                    for (int i = 0; i < reflected.remaining(); i++) {
                        var resource = reflected.get(i);
                        String name = resource.nameString();
                        long type = Spvc.spvc_compiler_get_type_handle(reflection, resource.type_id());
                        if (Spvc.spvc_type_get_num_array_dimensions(type) != 0) {
                            throw new BackendUnavailableException("Native descriptor arrays are not supported: "
                                    + name);
                        }
                        int dimension = resourceType == Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE
                                || resourceType == Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE
                                || resourceType == Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE
                                ? Spvc.spvc_type_get_image_dimension(type) : Integer.MAX_VALUE;
                        int descriptorType = descriptorType(resourceType, dimension);
                        var shape = new DescriptorShape(descriptorType, dimension);
                        DescriptorShape oldShape = shapes.putIfAbsent(name, shape);
                        if (oldShape != null && !oldShape.equals(shape)) {
                            throw new BackendUnavailableException("Native descriptor differs between stages: " + name);
                        }
                        EngineBinding requested = requestedEngineBindings.get(name);
                        int binding;
                        if (requested != null) {
                            if (requested.descriptorType != descriptorType) {
                                throw new BackendUnavailableException("Engine descriptor type mismatch: " + name);
                            }
                            binding = requested.binding;
                            int inputIndex = resourceType == Spvc.SPVC_RESOURCE_TYPE_SUBPASS_INPUT
                                    ? Spvc.spvc_compiler_get_decoration(reflection, resource.id(),
                                    Spv.SpvDecorationInputAttachmentIndex) : -1;
                            var previous = engineResources.get(binding);
                            if (previous != null && !previous.name.equals(name)) {
                                throw new BackendUnavailableException("Engine descriptor binding collision: "
                                        + binding);
                            }
                            engineResources.put(binding, new EngineResource(name, binding, descriptorType, inputIndex,
                                    stage | (previous == null ? 0 : previous.stageFlags)));
                        } else {
                            var declaration = declarations.get(name);
                            if (declaration == null) {
                                throw new BackendUnavailableException("Undeclared native Vulkan descriptor: " + name);
                            }
                            int expected = switch (declaration.type()) {
                                case UNIFORM_BUFFER -> VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
                                case COMBINED_IMAGE_SAMPLER -> VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                                case TEXEL_BUFFER -> VK12.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
                            };
                            if (descriptorType != expected) {
                                throw new BackendUnavailableException("Native Vulkan descriptor type mismatch: "
                                        + name);
                            }
                            binding = nativeIndices.getInt(name);
                            if (binding < 0) {
                                binding = nativeUniforms.size();
                                if (binding >= ENGINE_BINDING_BASE) {
                                    throw new BackendUnavailableException(
                                            "Native uniforms overlap engine descriptor bindings");
                                }
                                nativeIndices.put(name, binding);
                                nativeUniforms.add(declaration);
                            }
                        }
                        var previous = bindings.get(binding);
                        if (previous != null && previous.type() != descriptorType) {
                            throw new BackendUnavailableException("Native Vulkan descriptor binding collision: "
                                    + binding);
                        }
                        bindings.put(binding, new VkDescriptorLayout.Binding(binding, descriptorType,
                                stage | (previous == null ? 0 : previous.stageFlags())));
                        writeDecoration(reflection, resource.id(), Spv.SpvDecorationBinding, binding, words,
                                decorationOffset);
                        writeDecoration(reflection, resource.id(), Spv.SpvDecorationDescriptorSet, 0, words,
                                decorationOffset);
                    }
                }
            } finally {
                if (context != 0) Spvc.spvc_context_destroy(context);
            }
        }
    }

    private record DescriptorShape(int type, int dimension) {
    }

    private static int descriptorType(int resourceType, int dimension) {
        return switch (resourceType) {
            case Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER -> VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER -> VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case Spvc.SPVC_RESOURCE_TYPE_SUBPASS_INPUT -> VK12.VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT;
            case Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE -> dimension == Spv.SpvDimBuffer
                    ? VK12.VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER : VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            case Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE -> dimension == Spv.SpvDimBuffer
                    ? VK12.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER : VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE -> VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            case Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS -> VK12.VK_DESCRIPTOR_TYPE_SAMPLER;
            default -> throw new IllegalArgumentException("Unsupported native SPIR-V resource type " + resourceType);
        };
    }

    private static void writeDecoration(long compiler, int resource, int decoration, int value,
                                         IntBuffer words, IntBuffer offset) {
        if (!Spvc.spvc_compiler_get_binary_offset_for_decoration(compiler, resource, decoration, offset)) {
            throw new BackendUnavailableException("Native descriptor is missing SPIR-V decoration " + decoration);
        }
        words.put(offset.get(0), value);
    }

    private static void check(int result) {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new BackendUnavailableException("Native SPIR-V reflection failed with error " + result);
        }
    }

    @Override
    public void close() {
        compiler.close();
    }

    public record EngineBinding(int binding, int descriptorType) {
        public EngineBinding {
            if (binding < ENGINE_BINDING_BASE) {
                throw new IllegalArgumentException("Engine descriptor binding must be at least " + ENGINE_BINDING_BASE);
            }
        }
    }

    public record EngineResource(String name, int binding, int descriptorType, int inputAttachmentIndex,
            int stageFlags) {
    }

    /** {@code renderPass == 0}: dynamic rendering; else the fused pass owner's subpass. */
    public record Compatibility(long renderPass, int subpass, int depthFormat,
                                List<@Nullable ColorTargetState> colorTargets) {
        public Compatibility {
            colorTargets = Collections.unmodifiableList(new ArrayList<>(colorTargets));
        }
    }

    public record CompiledSources(Map<ShaderType, SpvModule> shaders,
                                   List<BackendRenderPipeline.CreateInfo.VertexBuffer> vertexBuffers,
                                   List<BackendRenderPipeline.CreateInfo.AttribBinding> attributes,
                                   Object2IntMap<String> nativeUniformIndices,
                                   List<BindGroupLayout.UniformDescription> nativeUniforms,
                                   List<VkDescriptorLayout.Binding> descriptorBindings,
                                   List<EngineResource> engineResources, int pushConstantSize)
            implements AutoCloseable {
        @Override
        public void close() {
            shaders.values().forEach(SpvModule::close);
        }
    }
}
