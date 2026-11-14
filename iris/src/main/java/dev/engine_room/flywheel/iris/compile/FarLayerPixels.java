package dev.engine_room.flywheel.iris.compile;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.BindlessSlots;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

/**
 * Groups a complete reconstructed-hit window by source texture. Render thread; records remain independently shaded.
 */
public final class FarLayerPixels implements AutoCloseable {
    public static final int HIT_BINDING = 24;
    public static final int MAPPING_BINDING = 25;
    public static final int HIT_BYTES = 112;
    public static final int HEADER_BYTES = 16;
    static final String RECORD = """
            struct _FlwFarHit {
                vec4 color;
                vec4 normal;
                vec4 light;
                uvec4 material;
                vec4 worldDepth;
                uvec4 address;
                vec4 tangent;
            };
            """;
    public static final String HITS = RECORD + """
            layout(std430, binding = 24) readonly buffer _FlwFarHits {
                uvec4 _flw_farHitHeader;
                _FlwFarHit _flw_farHits[];
            };
            """;
    public static final String VERTEX = """
            layout(std430, binding = 25) readonly buffer _FlwFarHitMapping { uint _flw_farHitMapping[]; };
            flat out uint _flw_farHitIndex;
            flat out uint _flw_farTextureSlot;
            uniform vec2 _flw_farHitSize;
            vec2 _flw_farTaa();
            const vec2 _flw_farHitCorners[6] = vec2[6](
                    vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0),
                    vec2(0.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0));
            void main() {
                _flw_farHitIndex = _flw_farHitMapping[uint(gl_BaseInstanceARB) + uint(gl_InstanceID)];
                _FlwFarHit hit = _flw_farHits[_flw_farHitIndex];
                uint pixel = hit.address.w;
                uvec2 at = uvec2(pixel % uint(_flw_farHitSize.x), pixel / uint(_flw_farHitSize.x));
                vec2 corner = (vec2(at) + _flw_farHitCorners[gl_VertexID]) / _flw_farHitSize;
                gl_Position = vec4(corner * 2.0 - 1.0 + _flw_farTaa(), 0.0, 1.0);
                _flw_farTextureSlot = uint(gl_DrawIDARB) + 16u;
            }
            """;
    private static final String COMMANDS = """
            struct _FlwFarMaterialCommand { uint count; uint instanceCount; uint first; uint baseInstance; };
            layout(std430, binding = 0) buffer _FlwFarHitCommands { _FlwFarMaterialCommand _flw_farHitCommands[]; };
            """;
    private static final String CURSORS = """
            layout(std430, binding = 2) buffer _FlwFarHitCursors { uint _flw_farHitCursors[]; };
            """;
    private final AsyncGlProgram count = compute("count", HITS, COMMANDS, """
            layout(local_size_x = 256) in;
            void main() {
                uint index = gl_GlobalInvocationID.x;
                if (index >= _flw_farHitHeader.x) return;
                uint material = (_flw_farHits[index].material.z & 65535u) - 16u;
                atomicAdd(_flw_farHitCommands[material].instanceCount, 1u);
            }
            """);
    private final AsyncGlProgram prefix = compute("prefix", COMMANDS, CURSORS, """
            layout(local_size_x = 1) in;
            void main() {
                uint offset = 0u;
                for (uint material = 0u; material < uint(_flw_farHitCommands.length()); material++) {
                    _flw_farHitCommands[material].count = 6u;
                    _flw_farHitCommands[material].first = 0u;
                    _flw_farHitCommands[material].baseInstance = offset;
                    offset += _flw_farHitCommands[material].instanceCount;
                    _flw_farHitCursors[material] = 0u;
                }
            }
            """);
    private final AsyncGlProgram scatter = compute("scatter", HITS, COMMANDS, CURSORS, """
            layout(local_size_x = 256) in;
            layout(std430, binding = 25) writeonly buffer _FlwFarHitMapping { uint _flw_farHitMapping[]; };
            void main() {
                uint index = gl_GlobalInvocationID.x;
                if (index >= _flw_farHitHeader.x) return;
                uint material = (_flw_farHits[index].material.z & 65535u) - 16u;
                uint target = _flw_farHitCommands[material].baseInstance
                        + atomicAdd(_flw_farHitCursors[material], 1u);
                _flw_farHitMapping[target] = index;
            }
            """);
    private final int commands = GL45C.glCreateBuffers();
    private final int mapping = GL45C.glCreateBuffers();
    private final int cursors = GL45C.glCreateBuffers();
    private int capacity;
    private int materials;

    public boolean ready() {
        return count.get() != null && prefix.get() != null && scatter.get() != null;
    }

    /**
     * After readiness, groups all records in a window whose allocation bounds its GPU header count.
     */
    public void prepare(int hits, int maximumHits) {
        int materialCount = BindlessSlots.count();
        if (capacity < maximumHits) {
            GL45C.glNamedBufferData(mapping, (long) maximumHits * Integer.BYTES, GL15C.GL_DYNAMIC_DRAW);
            capacity = maximumHits;
        }
        if (materials != materialCount) {
            GL45C.glNamedBufferData(commands, (long) materialCount * 16, GL15C.GL_DYNAMIC_DRAW);
            GL45C.glNamedBufferData(cursors, (long) materialCount * Integer.BYTES, GL15C.GL_DYNAMIC_DRAW);
            materials = materialCount;
        }
        GL45C.glClearNamedBufferData(commands, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT,
                new int[]{0});
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, HIT_BINDING, hits);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, commands);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, MAPPING_BINDING, mapping);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, cursors);
        count.get().bind();
        GL43C.glDispatchCompute((maximumHits + 255) / 256, 1, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        prefix.get().bind();
        GL43C.glDispatchCompute(1, 1, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        scatter.get().bind();
        GL43C.glDispatchCompute((maximumHits + 255) / 256, 1, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_COMMAND_BARRIER_BIT);
    }

    public void draw() {
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, MAPPING_BINDING, mapping);
        GlStateManager._glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, commands);
        GL43C.glMultiDrawArraysIndirect(GL11C.GL_TRIANGLES, 0L, materials, 16);
    }

    private static AsyncGlProgram compute(String name, String... components) {
        var source = new Compilation();
        source.version(GlslVersion.V430);
        for (int i = 0; i < components.length; i++)
            source.appendComponent(new RawSource("far_layer_" + name + "_" + i, components[i]));
        return AsyncGlProgram.compute("iris/far_layer_" + name, source, _ -> {
        });
    }

    @Override
    public void close() {
        count.close();
        prefix.close();
        scatter.close();
        GL15C.glDeleteBuffers(commands);
        GL15C.glDeleteBuffers(mapping);
        GL15C.glDeleteBuffers(cursors);
    }
}
