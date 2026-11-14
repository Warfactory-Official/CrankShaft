package dev.engine_room.flywheel.iris.compile;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.engine.BindlessSlots;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.impl.far.FarSurface;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

/**
 * Groups retained pixels by texture slot, making each bindless material sampler uniform within its draw.
 */
public final class FarMaterialPixels implements AutoCloseable {
    private static final String COMMANDS = """
            struct FarMaterialCommand {
                uint count;
                uint instanceCount;
                uint first;
                uint baseInstance;
            };
            layout(std430, binding = 0) buffer FarMaterialCommands {
                FarMaterialCommand commands[];
            };
            """;
    private static final String PIXELS = """
            layout(binding = 0) uniform usampler2D _flw_farMaterial;
            layout(binding = 5) uniform sampler2D _flw_farDepth;
            """;
    private static final String CURSORS = """
            layout(std430, binding = 2) buffer FarMaterialCursors {
                uint cursors[];
            };
            """;
    private static final String COUNT = """
            layout(local_size_x = 256) in;
            void main() {
                ivec2 size = textureSize(_flw_farDepth, 0);
                uint pixel = gl_GlobalInvocationID.x;
                if (pixel >= uint(size.x * size.y)) return;
                ivec2 at = ivec2(int(pixel) % size.x, int(pixel) / size.x);
                if (texelFetch(_flw_farDepth, at, 0).r == 0.0) return;
                uint material = (texelFetch(_flw_farMaterial, at, 0).z & 65535u) - 16u;
                atomicAdd(commands[material].instanceCount, 1u);
            }
            """;
    private static final String PREFIX = """
            layout(local_size_x = 1) in;
            void main() {
                uint offset = 0u;
                for (uint material = 0u; material < uint(commands.length()); material++) {
                    commands[material].count = 6u;
                    commands[material].first = 0u;
                    commands[material].baseInstance = offset;
                    offset += commands[material].instanceCount;
                    cursors[material] = 0u;
                }
            }
            """;
    private static final String SCATTER = """
            layout(local_size_x = 256) in;
            layout(std430, binding = 1) writeonly buffer FarMaterialPixels {
                uint pixels[];
            };
            void main() {
                ivec2 size = textureSize(_flw_farDepth, 0);
                uint pixel = gl_GlobalInvocationID.x;
                if (pixel >= uint(size.x * size.y)) return;
                ivec2 at = ivec2(int(pixel) % size.x, int(pixel) / size.x);
                if (texelFetch(_flw_farDepth, at, 0).r == 0.0) return;
                uint material = (texelFetch(_flw_farMaterial, at, 0).z & 65535u) - 16u;
                uint index = commands[material].baseInstance + atomicAdd(cursors[material], 1u);
                pixels[index] = pixel;
            }
            """;

    /**
     * Requires ARB_shader_draw_parameters; the owning shader supplies the pack's jitter function.
     */
    public static final String VERTEX = """
            layout(std430, binding = 1) readonly buffer FarMaterialPixels {
                uint pixels[];
            };
            layout(binding = 5) uniform sampler2D _flw_farDepth;
            flat out uint _flw_farTextureSlot;
            vec2 _flw_farTaa();
            const vec2 _flw_farCorners[6] = vec2[6](
                    vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0),
                    vec2(0.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0));
            void main() {
                ivec2 size = textureSize(_flw_farDepth, 0);
                uint pixel = pixels[uint(gl_BaseInstanceARB) + uint(gl_InstanceID)];
                ivec2 at = ivec2(int(pixel) % size.x, int(pixel) / size.x);
                vec2 corner = (vec2(at) + _flw_farCorners[gl_VertexID]) / vec2(size);
                gl_Position = vec4(corner * 2.0 - 1.0 + _flw_farTaa(), 0.0, 1.0);
                _flw_farTextureSlot = uint(gl_DrawIDARB) + 16u;
            }
            """;

    private final AsyncGlProgram count = program("count", COMMANDS, PIXELS, COUNT);
    private final AsyncGlProgram prefix = program("prefix", COMMANDS, CURSORS, PREFIX);
    private final AsyncGlProgram scatter = program("scatter", COMMANDS, PIXELS, CURSORS, SCATTER);
    private final int commands = GL45C.glCreateBuffers();
    private final int pixels = GL45C.glCreateBuffers();
    private final int cursors = GL45C.glCreateBuffers();
    private int pixelCapacity;
    private int materialCount;
    private FarSurface prepared;

    public boolean ready() {
        return count.get() != null && prefix.get() != null && scatter.get() != null;
    }

    /**
     * After readiness; material and depth textures must be bound at units 0 and 5 by the caller. Render thread.
     */
    public void prepare(FarSurface surface) {
        int materials = BindlessSlots.count();
        if (prepared == surface && materialCount == materials) return;
        int size = surface.width() * surface.height();
        if (pixelCapacity != size) {
            GL45C.glNamedBufferData(pixels, (long) size * Integer.BYTES, GL15C.GL_DYNAMIC_DRAW);
            pixelCapacity = size;
        }
        if (materialCount != materials) {
            GL45C.glNamedBufferData(commands, (long) materials * 16, GL15C.GL_DYNAMIC_DRAW);
            GL45C.glNamedBufferData(cursors, (long) materials * Integer.BYTES, GL15C.GL_DYNAMIC_DRAW);
            materialCount = materials;
        }
        GL45C.glClearNamedBufferData(commands, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT,
                new int[]{0});
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, commands);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, pixels);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, cursors);
        int groups = (size + 255) / 256;
        count.get().bind();
        GL43C.glDispatchCompute(groups, 1, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        prefix.get().bind();
        GL43C.glDispatchCompute(1, 1, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        scatter.get().bind();
        GL43C.glDispatchCompute(groups, 1, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_COMMAND_BARRIER_BIT);
        prepared = surface;
    }

    public void bind() {
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, pixels);
        GlStateManager._glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, commands);
    }

    public void draw() {
        GL43C.glMultiDrawArraysIndirect(GL11C.GL_TRIANGLES, 0L, materialCount, 16);
    }

    @Override
    public void close() {
        count.close();
        prefix.close();
        scatter.close();
        GL15C.glDeleteBuffers(commands);
        GL15C.glDeleteBuffers(pixels);
        GL15C.glDeleteBuffers(cursors);
    }

    private static AsyncGlProgram program(String name, String... components) {
        var compilation = new Compilation();
        compilation.version(GlslVersion.V430);
        for (int i = 0; i < components.length; i++)
            compilation.appendComponent(new RawSource("far_material_" + name + "_" + i, components[i]));
        return AsyncGlProgram.compute("iris/far_material_" + name, compilation, _ -> {
        });
    }
}
