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
import dev.engine_room.flywheel.backend.engine.MaterialEncoder;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedDraw;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.ComputePipeline;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanShaderPatcher;
import net.irisshaders.iris.samplers.ProgramResources;
import net.irisshaders.iris.uniforms.buffer.BufferUniformHolder;
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
    private final Map<InstanceType<?>, Program> programs = new HashMap<>();
    private final Map<ExperimentalInstancer<?>, Data> data = new HashMap<>();
    private final ByteBuffer parameters = ByteBuffer.allocateDirect(96).order(ByteOrder.nativeOrder());
    private ByteBuffer commandData = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
    private GpuBuffer visible;
    private GpuBuffer commands;
    private GpuBuffer drawIndices;
    private ByteBuffer drawIndexData = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
    private final Map<InstancedDraw, Integer> commandIndices = new IdentityHashMap<>();

    private static GpuBuffer grow(GpuBuffer old, long bytes, String name) {
        if (old != null && old.size() >= bytes) return old;
        GpuBuffer next = Iris.getRenderBackend().createStorageBuffer(name, Math.max(256, bytes), null);
        if (old != null) Iris.getRenderBackend().retire(old::close);
        return next;
    }

    void cull(Collection<ExperimentalInstancer<?>> instancers, List<InstancedDraw> draws, Matrix4fc viewProjection) {
        programs.values().forEach(program -> program.models.clear());
        int instanceCount = 0;
        int drawCount = 0;
        for (var instancer : instancers) {
            if (instancer.draws().isEmpty()) continue;
            Program program = programs.computeIfAbsent(instancer.type, Program::new);
            program.models.add(instancer);
            Data model = data.computeIfAbsent(instancer, _ -> new Data());
            model.visibleBase = instanceCount;
            model.commandBase = drawCount;
            instanceCount = Math.addExact(instanceCount, instancer.instanceCount());
            drawCount = Math.addExact(drawCount, instancer.draws().size());
        }
        if (drawCount == 0) return;
        visible = grow(visible, (long) instanceCount * 4, "flywheel visible guest instances");
        commands = grow(commands, (long) drawCount * COMMAND_BYTES, "flywheel guest draw commands");
        drawIndices = grow(drawIndices, (long) drawCount * 4, "flywheel guest model draws");
        if (commandData.capacity() < Math.multiplyExact(drawCount, COMMAND_BYTES))
            commandData = ByteBuffer.allocateDirect(Math.multiplyExact(drawCount, COMMAND_BYTES))
                                    .order(ByteOrder.nativeOrder());
        if (drawIndexData.capacity() < Math.multiplyExact(drawCount, 4))
            drawIndexData = ByteBuffer.allocateDirect(Math.multiplyExact(drawCount, 4)).order(ByteOrder.nativeOrder());
        commandData.clear();
        commandIndices.clear();
        for (int index = 0; index < draws.size(); index++) {
            var draw = draws.get(index);
            var instancer = (ExperimentalInstancer<?>) draw.instancer();
            var model = data.get(instancer);
            commandIndices.put(draw, index);
            commandData.putInt(draw.mesh().indexCount()).putInt(0).putInt(draw.mesh().firstIndex())
                       .putInt(draw.mesh().baseVertex()).putInt(model.visibleBase).putInt(0)
                       .putInt(instancer.environment.matrixIndex())
                       .putInt(MaterialEncoder.packUberShader(draw.material()))
                       .putInt(MaterialEncoder.packProperties(draw.material()))
                       .putInt(draw.tags() == null ? 0 : draw.tags().itemTag())
                       .putInt(draw.tags() == null ? 0 : draw.tags().drawTag()).putInt(instancer.typeId << 16);
        }
        drawIndexData.clear();
        for (var instancer : instancers)
            for (var draw : instancer.draws()) drawIndexData.putInt(commandIndices.get(draw));
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToBuffer(commands.slice(0, commandData.position()), commandData.flip());
        encoder.writeToBuffer(drawIndices.slice(0, drawIndexData.position()), drawIndexData.flip());
        parameters.clear();
        Vector4f plane = new Vector4f();
        Matrix4f projection = new Matrix4f(viewProjection);
        for (int i = 0; i < 6; i++) {
            projection.frustumPlane(i, plane);
            parameters.putFloat(plane.x).putFloat(plane.y).putFloat(plane.z).putFloat(plane.w);
        }
        parameters.flip();
        var params = encoder.transientMemory().uploadGpu(parameters,
                RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
        for (var program : programs.values()) {
            if (program.models.isEmpty()) continue;
            int bytes = Math.multiplyExact(program.models.size(), MODEL_BYTES);
            program.modelBuffer = grow(program.modelBuffer, bytes, "flywheel guest culling models");
            if (program.modelData.capacity() < bytes)
                program.modelData = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            program.modelData.clear();
            int maxInstances = 0;
            for (var instancer : program.models) {
                Data model = data.get(instancer);
                Matrix4f embed = instancer.environment instanceof EmbeddedEnvironment embedded
                        ? new Matrix4f(embedded.pose()) : new Matrix4f();
                int start = program.modelData.position();
                embed.get(start, program.modelData);
                program.modelData.position(start + 64);
                program.modelData.putFloat(instancer.bounds.x()).putFloat(instancer.bounds.y())
                                 .putFloat(instancer.bounds.z()).putFloat(instancer.bounds.w());
                program.modelData.putInt(instancer.baseWord()).putInt(model.visibleBase).putInt(model.commandBase)
                                 .putInt(instancer.strideWords());
                program.modelData.putInt(instancer.instanceCount()).putInt(instancer.draws().size()).putInt(0)
                                 .putInt(0);
                maxInstances = Math.max(maxInstances, instancer.instanceCount());
            }
            encoder.writeToBuffer(program.modelBuffer.slice(0, bytes), program.modelData.flip());
            program.buffers = Map.of("InstanceBuffer", List.of(program.models.getFirst().instanceTexels().slice()),
                    "_FlwVisibleInstances", List.of(visible.slice()), "_FlwGuestCommands", List.of(commands.slice()),
                    "_FlwGuestModels", List.of(program.modelBuffer.slice()), "_FlwGuestCull", List.of(params),
                    "_FlwGuestDrawIndices", List.of(drawIndices.slice()));
            Iris.getRenderBackend().commands().dispatch(program.pipeline,
                    new Vector3i((maxInstances + 63) / 64, program.models.size(), 1), null);
        }
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
    }

    private static final class Program {
        final ComputePipeline pipeline;
        final List<ExperimentalInstancer<?>> models = new ArrayList<>();
        ByteBuffer modelData = ByteBuffer.allocateDirect(MODEL_BYTES).order(ByteOrder.nativeOrder());
        GpuBuffer modelBuffer;
        Map<String, List<GpuBufferSlice>> buffers = Map.of();

        Program(InstanceType<?> type) {
            String source = ShaderAssembly.assemble(_ -> {
            }, List.of(new InstanceStructComponent(type),
                    new SsboInstanceComponent(type), FlwPrograms.SOURCES.get(type.cullShader()),
                    FlwPrograms.SOURCES.get(ResourceUtil.rl("iris/guest_cull.comp"))));
            source = NativeShaderPreprocessor.preprocessKind(source, "flywheel:iris/cull",
                    Shaderc.shaderc_compute_shader, Map.of());
            var sources = Map.of(PatchShaderType.COMPUTE, source);
            var layout = VulkanShaderPatcher.discover(sources).layout();
            var parent = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
            var uniforms = new BufferUniformHolder(layout);
            var resources = new ProgramResources(parent.getPackSamplers(), layout);
            resources.flywheel$externalStorage(
                    Set.of("InstanceBuffer", "_FlwVisibleInstances", "_FlwGuestCommands", "_FlwGuestModels",
                            "_FlwGuestDrawIndices"));
            resources.setUniformBuffers(() -> buffers);
            resources.finish();
            var patched = TransformPatcher.patchProgram("flywheel_iris_cull", sources, resources, uniforms, null);
            pipeline = Iris.getRenderBackend()
                           .createComputePipeline("flywheel:iris/cull", patched, resources, uniforms);
        }
    }
}
