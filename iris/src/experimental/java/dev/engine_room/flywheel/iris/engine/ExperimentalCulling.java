package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.NativeShaderPreprocessor;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.component.InstanceStructComponent;
import dev.engine_room.flywheel.backend.compile.component.SsboInstanceComponent;
import dev.engine_room.flywheel.backend.engine.BindlessSlots;
import dev.engine_room.flywheel.backend.engine.MaterialEncoder;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedDraw;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.iris.compile.ExperimentalBindless;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.ComputePipeline;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanShaderPatcher;
import net.irisshaders.iris.samplers.ProgramResources;
import net.irisshaders.iris.uniforms.buffer.BufferUniformHolder;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One visibility dispatch per type over shared instance, visibility and indirect arenas.
 */
final class ExperimentalCulling implements AutoCloseable {
    static final int COMMAND_BYTES = 48;
    private static final int MODEL_BYTES = 112;
    private static final int ORDER_BYTES = 32;
    private final Map<InstanceType<?>, Program> programs = new HashMap<>();
    private final Map<ExperimentalInstancer<?>, Data> data = new HashMap<>();
    private final List<ExperimentalInstancer<?>> ordered = new ArrayList<>();
    private final ByteBuffer parameters = ByteBuffer.allocateDirect(96).order(ByteOrder.nativeOrder());
    private ByteBuffer commandData = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
    private ByteBuffer orderData = ByteBuffer.allocateDirect(ORDER_BYTES).order(ByteOrder.nativeOrder());
    private GpuBuffer visible;
    private GpuBuffer commands;
    private GpuBuffer drawIndices;
    private GpuBuffer orders;
    private GpuBuffer orderWords;
    private Program order;
    private ByteBuffer drawIndexData = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
    private final Map<InstancedDraw, Integer> commandIndices = new IdentityHashMap<>();
    private final Matrix4f matrix = new Matrix4f();
    private final Vector4f plane = new Vector4f();
    private Program reset;
    private int drawCount;

    private static GpuBuffer grow(GpuBuffer old, long bytes, String name) {
        if (old != null && old.size() >= bytes) return old;
        GpuBuffer next = Iris.getRenderBackend().createStorageBuffer(name, Math.max(256, bytes), null);
        if (old != null) Iris.getRenderBackend().retire(old::close);
        return next;
    }

    void prepare(Collection<ExperimentalInstancer<?>> instancers, List<InstancedDraw> draws, boolean changed) {
        programs.values().forEach(program -> program.models.clear());
        ordered.clear();
        int instanceCount = 0;
        int modelDrawCount = 0;
        int orderWordCount = 0;
        for (var instancer : instancers) {
            if (instancer.draws().isEmpty()) continue;
            Program program = programs.computeIfAbsent(instancer.type, Program::new);
            program.models.add(instancer);
            Data model = data.computeIfAbsent(instancer, _ -> new Data());
            model.visibleBase = instanceCount;
            // A map resize can reorder surviving instancers without changing the draw list.
            changed |= model.commandBase != modelDrawCount;
            model.commandBase = modelDrawCount;
            if (instancer.ordered) {
                model.orderWord = orderWordCount;
                orderWordCount = Math.addExact(orderWordCount, Math.ceilDiv(instancer.instanceCount(), 32));
                ordered.add(instancer);
            }
            instanceCount = Math.addExact(instanceCount, instancer.instanceCount());
            modelDrawCount = Math.addExact(modelDrawCount, instancer.draws().size());
        }
        changed |= drawCount != draws.size();
        drawCount = draws.size();
        if (drawCount == 0) return;
        visible = grow(visible, (long) instanceCount * 4, "flywheel visible guest instances");
        commands = grow(commands, (long) drawCount * COMMAND_BYTES, "flywheel guest draw commands");
        drawIndices = grow(drawIndices, (long) drawCount * 4, "flywheel guest model draws");
        orderWords = grow(orderWords, (long) orderWordCount * 4, "flywheel ordered guest visibility");
        if (commandData.capacity() < Math.multiplyExact(drawCount, COMMAND_BYTES))
            commandData = ByteBuffer.allocateDirect(Math.multiplyExact(drawCount, COMMAND_BYTES))
                                    .order(ByteOrder.nativeOrder());
        if (drawIndexData.capacity() < Math.multiplyExact(drawCount, 4))
            drawIndexData = ByteBuffer.allocateDirect(Math.multiplyExact(drawCount, 4)).order(ByteOrder.nativeOrder());
        commandData.clear().limit(Math.multiplyExact(drawCount, COMMAND_BYTES));
        boolean upload = changed;
        boolean bindless = ExperimentalBindless.available();
        if (changed) commandIndices.clear();
        for (int index = 0; index < draws.size(); index++) {
            var draw = draws.get(index);
            var instancer = (ExperimentalInstancer<?>) draw.instancer();
            var model = data.get(instancer);
            int offset = index * COMMAND_BYTES;
            if (changed) {
                commandIndices.put(draw, index);
                commandData.position(offset);
                commandData.putInt(draw.mesh().indexCount()).putInt(0).putInt(draw.mesh().firstIndex())
                           .putInt(draw.mesh().baseVertex()).putInt(model.visibleBase).putInt(0)
                           .putInt(instancer.environment.matrixIndex())
                           .putInt(MaterialEncoder.packUberShader(draw.material()))
                           .putInt(MaterialEncoder.packProperties(draw.material()))
                           .putInt(draw.tags() == null ? 0 : draw.tags().itemTag())
                           .putInt(draw.tags() == null ? 0 : draw.tags().drawTag())
                           .putInt(instancer.typeId << 16 | (bindless
                                   ? BindlessSlots.slot(draw.material()) : 0));
            } else {
                // Mesh offsets, environment slots and material values may change without a draw-list edit.
                upload |= updateCommand(offset, draw.mesh().indexCount());
                upload |= updateCommand(offset + 8, draw.mesh().firstIndex());
                upload |= updateCommand(offset + 12, draw.mesh().baseVertex());
                upload |= updateCommand(offset + 16, model.visibleBase);
                upload |= updateCommand(offset + 24, instancer.environment.matrixIndex());
                upload |= updateCommand(offset + 28, MaterialEncoder.packUberShader(draw.material()));
                upload |= updateCommand(offset + 32, MaterialEncoder.packProperties(draw.material()));
                upload |= updateCommand(offset + 44,
                        instancer.typeId << 16 | (bindless ? BindlessSlots.slot(draw.material()) : 0));
            }
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (upload) encoder.writeToBuffer(commands.slice(0, commandData.limit()), commandData.position(0));
        if (changed) {
            drawIndexData.clear();
            for (var instancer : instancers)
                for (var draw : instancer.draws()) drawIndexData.putInt(commandIndices.get(draw));
            encoder.writeToBuffer(drawIndices.slice(0, drawIndexData.position()), drawIndexData.flip());
        }
        for (var program : programs.values()) {
            if (program.models.isEmpty()) continue;
            int bytes = Math.multiplyExact(program.models.size(), MODEL_BYTES);
            program.modelBuffer = grow(program.modelBuffer, bytes, "flywheel guest culling models");
            if (program.modelData.capacity() < bytes)
                program.modelData = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            program.modelData.clear();
            program.maxInstances = 0;
            for (var instancer : program.models) {
                Data model = data.get(instancer);
                if (instancer.environment instanceof EmbeddedEnvironment embedded) matrix.set(embedded.pose());
                else matrix.identity();
                int start = program.modelData.position();
                matrix.get(start, program.modelData);
                program.modelData.position(start + 64);
                program.modelData.putFloat(instancer.bounds.x()).putFloat(instancer.bounds.y())
                                 .putFloat(instancer.bounds.z()).putFloat(instancer.bounds.w());
                program.modelData.putInt(instancer.baseWord()).putInt(model.visibleBase).putInt(model.commandBase)
                                 .putInt(instancer.strideWords());
                program.modelData.putInt(instancer.instanceCount()).putInt(instancer.draws().size())
                                 .putInt(instancer.ordered ? 1 : 0).putInt(model.orderWord);
                program.maxInstances = Math.max(program.maxInstances, instancer.instanceCount());
            }
            encoder.writeToBuffer(program.modelBuffer.slice(0, bytes), program.modelData.flip());
            program.buffers.put("InstanceBuffer", List.of(program.models.getFirst().instanceTexels().slice()));
            program.buffers.put("_FlwVisibleInstances", List.of(visible.slice()));
            program.buffers.put("_FlwGuestCommands", List.of(commands.slice()));
            program.buffers.put("_FlwGuestModels", List.of(program.modelBuffer.slice()));
            program.buffers.put("_FlwGuestDrawIndices", List.of(drawIndices.slice()));
            program.buffers.put("_FlwGuestOrderWords", List.of(orderWords.slice()));
        }
        if (ordered.isEmpty()) return;
        int orderBytes = Math.multiplyExact(ordered.size(), ORDER_BYTES);
        orders = grow(orders, orderBytes, "flywheel ordered guest models");
        if (orderData.capacity() < orderBytes)
            orderData = ByteBuffer.allocateDirect(orderBytes).order(ByteOrder.nativeOrder());
        orderData.clear();
        for (var instancer : ordered) {
            Data model = data.get(instancer);
            orderData.putInt(instancer.baseWord()).putInt(model.visibleBase).putInt(model.commandBase)
                     .putInt(instancer.strideWords());
            orderData.putInt(instancer.instanceCount()).putInt(instancer.draws().size()).putInt(model.orderWord)
                     .putInt(0);
        }
        encoder.writeToBuffer(orders.slice(0, orderBytes), orderData.flip());
        if (order == null) order = new Program(ResourceUtil.rl("iris/guest_order.comp"));
        order.buffers.put("_FlwGuestOrders", List.of(orders.slice()));
        order.buffers.put("_FlwGuestOrderWords", List.of(orderWords.slice()));
        order.buffers.put("_FlwVisibleInstances", List.of(visible.slice()));
        order.buffers.put("_FlwGuestCommands", List.of(commands.slice()));
        order.buffers.put("_FlwGuestDrawIndices", List.of(drawIndices.slice()));
    }

    private boolean updateCommand(int offset, int value) {
        if (commandData.getInt(offset) == value) return false;
        commandData.putInt(offset, value);
        return true;
    }

    void cull(Matrix4fc viewProjection) {
        if (drawCount == 0) return;
        if (reset == null) reset = new Program(ResourceUtil.rl("iris/guest_reset.comp"));
        reset.buffers.put("_FlwGuestCommands", List.of(commands.slice(0, (long) drawCount * COMMAND_BYTES)));
        Iris.getRenderBackend().commands().dispatch(reset.pipeline, new Vector3i((drawCount + 63) / 64, 1, 1), null);
        parameters.clear();
        matrix.set(viewProjection);
        for (int i = 0; i < 6; i++) {
            matrix.frustumPlane(i, plane);
            parameters.putFloat(plane.x).putFloat(plane.y).putFloat(plane.z).putFloat(plane.w);
        }
        var params = RenderSystem.getDevice().createCommandEncoder().transientMemory().uploadGpu(parameters.flip(),
                RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
        for (var program : programs.values()) {
            if (program.models.isEmpty()) continue;
            program.buffers.put("_FlwGuestCull", List.of(params));
            Iris.getRenderBackend().commands().dispatch(program.pipeline,
                    new Vector3i((program.maxInstances + 63) / 64, program.models.size(), 1), null);
        }
        if (!ordered.isEmpty())
            Iris.getRenderBackend().commands().dispatch(order.pipeline, new Vector3i(ordered.size(), 1, 1), null);
    }

    GpuBuffer visible() {
        return visible;
    }

    GpuBuffer commands() {
        return commands;
    }

    void remove(ExperimentalInstancer<?> instancer) {
        data.remove(instancer);
    }

    @Override
    public void close() {
        if (visible != null) Iris.getRenderBackend().retire(visible::close);
        if (commands != null) Iris.getRenderBackend().retire(commands::close);
        if (drawIndices != null) Iris.getRenderBackend().retire(drawIndices::close);
        if (orders != null) Iris.getRenderBackend().retire(orders::close);
        if (orderWords != null) Iris.getRenderBackend().retire(orderWords::close);
        if (reset != null) Iris.getRenderBackend().retire(reset.pipeline::close);
        if (order != null) Iris.getRenderBackend().retire(order.pipeline::close);
        for (var program : programs.values()) {
            if (program.modelBuffer != null) Iris.getRenderBackend().retire(program.modelBuffer::close);
            Iris.getRenderBackend().retire(program.pipeline::close);
        }
        data.clear();
        commandIndices.clear();
        programs.clear();
    }

    static final class Data {
        int visibleBase;
        int commandBase;
        int orderWord;
    }

    private static final class Program {
        final ComputePipeline pipeline;
        final List<ExperimentalInstancer<?>> models = new ArrayList<>();
        ByteBuffer modelData = ByteBuffer.allocateDirect(MODEL_BYTES).order(ByteOrder.nativeOrder());
        final Map<String, List<GpuBufferSlice>> buffers = new HashMap<>();
        GpuBuffer modelBuffer;
        int maxInstances;

        Program(InstanceType<?> type) {
            this(List.of(new InstanceStructComponent(type),
                    new SsboInstanceComponent(type),
                    InstanceStructComponent.typeShader(type, FlwPrograms.SOURCES.get(type.cullShader())),
                    FlwPrograms.SOURCES.get(ResourceUtil.rl("iris/guest_cull.comp"))));
        }

        Program(Identifier source) {
            this(List.of(FlwPrograms.SOURCES.get(source)));
        }

        private Program(List<SourceComponent> roots) {
            String source = ShaderAssembly.assemble(ShaderAssembly.NO_EXTRA, roots);
            source = NativeShaderPreprocessor.preprocessKind(source, "flywheel:iris/cull",
                    Shaderc.shaderc_compute_shader, Map.of());
            var sources = Map.of(PatchShaderType.COMPUTE, source);
            var layout = VulkanShaderPatcher.discover(sources).layout();
            var parent = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
            var uniforms = new BufferUniformHolder(layout);
            var resources = new ProgramResources(parent.getPackSamplers(), layout);
            resources.flywheel$externalStorage(
                    Set.of("InstanceBuffer", "_FlwVisibleInstances", "_FlwGuestCommands", "_FlwGuestModels",
                            "_FlwGuestDrawIndices", "_FlwGuestOrderWords", "_FlwGuestOrders"));
            resources.setUniformBuffers(() -> buffers);
            resources.finish();
            var patched = TransformPatcher.patchProgram("flywheel_iris_cull", sources, resources, uniforms, null);
            pipeline = Iris.getRenderBackend()
                           .createComputePipeline("flywheel:iris/cull", patched, resources, uniforms);
        }
    }
}
