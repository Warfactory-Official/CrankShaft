package dev.engine_room.flywheel.iris.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import dev.engine_room.flywheel.api.material.LightShader;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.MaterialShaders;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.backend.compile.ProgramGeneration;
import dev.engine_room.flywheel.backend.compile.ProgramVersions;
import dev.engine_room.flywheel.backend.compile.RenderPassShaders;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.MaterialSamplers;
import dev.engine_room.flywheel.backend.engine.far.FarGlState;
import dev.engine_room.flywheel.backend.engine.far.FarLayerView;
import dev.engine_room.flywheel.backend.engine.far.FarStore;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Evaluates original animated mesh/material stages into independent, unlit records before native pack shading.
 */
final class FarLayerMeshCapture implements AutoCloseable {
    private final Map<Key, ProgramVersions<AsyncGlProgram[]>> programs = new HashMap<>();
    private final AsyncGlProgram slice;
    private final int command = GL45C.glCreateBuffers();

    FarLayerMeshCapture() {
        var source = new Compilation();
        source.version(GlslVersion.V430);
        source.appendComponent(new RawSource("far mesh command window", """
                layout(local_size_x=1) in;
                layout(std430,binding=0) readonly buffer Source { uvec4 source[]; };
                layout(std430,binding=3) writeonly buffer Window {
                    uvec4 windowDraw;
                    uvec4 windowInstance;
                    uvec4 windowMaterial;
                };
                uniform uint sourceWord;
                uniform uint firstPrimitive;
                uniform uint primitiveCount;
                uniform uint firstInstance;
                uniform uint instanceCount;
                void main() {
                    uint record=sourceWord>>2u;
                    uvec4 draw=source[record];
                    uvec4 instance=source[record+1u];
                    uvec4 material=source[record+2u];
                    uint count=draw.x/3u;
                    windowDraw=uvec4(
                            firstPrimitive>=count?0u:3u*min(primitiveCount,count-firstPrimitive),
                            firstInstance>=draw.y?0u:min(instanceCount,draw.y-firstInstance),
                            draw.z+firstPrimitive*3u,draw.w);
                    windowInstance=uvec4(instance.x+firstInstance,instance.yzw);
                    windowMaterial=material;
                }
                """));
        slice = AsyncGlProgram.compute("iris/far_mesh_window", source, _ -> {
        });
        GL45C.glNamedBufferData(command, 48, GL15C.GL_DYNAMIC_DRAW);
    }

    boolean drawReady() {
        return slice.get() != null;
    }

    boolean ready(FarStore store) {
        boolean[] ready = {slice.get() != null};
        store.forEachTranslucentMaterial(material -> {
            var versions = versions(material);
            var selected = versions.select(ProgramGeneration.current(), key(material), this::compile);
            ready[0] &= selected != null && versions.generation().covers(ProgramGeneration.current());
        });
        return ready[0];
    }

    void draw(FarStore store, FarLayerView view, FarLayerCapture capture, Consumer<FarLayerCapture.Window> shade) {
        if (!drawReady()) return;
        try (var _ = FarGlState.capture()) {
            store.forEachTranslucentMesh(view, batch -> drawBatch(store, view, batch, capture, shade));
        }
    }

    private void drawBatch(FarStore store, FarLayerView view, FarStore.MeshBatch batch, FarLayerCapture capture,
                           Consumer<FarLayerCapture.Window> shade) {
        if (batch.primitiveCount() == 0 || batch.maximumInstances() == 0) return;
        var versions = versions(batch.material());
        var selected = versions.select(ProgramGeneration.current(), key(batch.material()), this::compile);
        if (selected == null || !versions.generation().covers(batch.required())) return;
        var program = selected[0].get();
        int maximumRecords = (int) ((Math.min(16L * 1024 * 1024, GlCompat.MAX_SHADER_STORAGE_BLOCK_SIZE)
                - FarLayerPixels.HEADER_BYTES) / FarLayerPixels.HIT_BYTES);
        int desiredPixels = (int) Math.min((long) batch.width() * batch.height(), maximumRecords);
        int sourceBudget = Math.max(1, maximumRecords / desiredPixels);
        int instanceWindow = Math.min(batch.maximumInstances(), sourceBudget);
        int primitiveWindow = Math.min(batch.primitiveCount(), Math.max(1, sourceBudget / instanceWindow));
        int pixelWindow = maximumRecords / (instanceWindow * primitiveWindow);
        int width = Math.min(batch.width(), pixelWindow);
        int height = Math.min(batch.height(), pixelWindow / width);
        for (int firstInstance = 0; firstInstance < batch.maximumInstances(); firstInstance += instanceWindow) {
            int instances = Math.min(instanceWindow, batch.maximumInstances() - firstInstance);
            for (int firstPrimitive = 0; firstPrimitive < batch.primitiveCount(); firstPrimitive += primitiveWindow) {
                int primitives = Math.min(primitiveWindow, batch.primitiveCount() - firstPrimitive);
                window(batch, firstPrimitive, primitives, firstInstance, instances);
                for (int y = batch.y(); y < batch.y() + batch.height(); y += height) {
                    int rows = Math.min(height, batch.y() + batch.height() - y);
                    for (int x = batch.x(); x < batch.x() + batch.width(); x += width) {
                        int columns = Math.min(width, batch.x() + batch.width() - x);
                        int maximumHits = columns * rows * primitives * instances;
                        capture.begin(view, x, y, columns, rows, maximumHits);
                        program.bind();
                        store.bindTranslucentMesh(program, batch, view);
                        program.setUInt("_flw_farIrisWidth", view.width());
                        program.setUInt("_flw_farIrisSourcePrimitive", firstPrimitive);
                        program.setUInt("_flw_farIrisFirstPrimitive", 0);
                        program.setUInt("_flw_farIrisPrimitiveCount", primitives);
                        program.setUInt("_flw_farIrisMeshPrimitiveCount", primitives);
                        int flags = (batch.material().backfaceCulling() ? 1 : 0)
                                | (batch.material().polygonOffset() ? 1 << 1 : 0)
                                | batch.material().depthTest().ordinal() << 2
                                | batch.material().transparency().ordinal() << 8
                                | (batch.material().writeMask().depth() ? 1 << 16 : 0)
                                | (batch.material().writeMask().color() ? 1 << 17 : 0);
                        program.setUInt("_flw_farIrisFlags", flags);
                        material(program, batch.material());
                        GlStateManager._glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, command);
                        GL43C.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, 0L, 1, 48);
                        int hits = capture.finish();
                        shade.accept(new FarLayerCapture.Window(hits, maximumHits, view));
                    }
                }
            }
        }
    }

    private void window(FarStore.MeshBatch batch, int firstPrimitive, int primitives, int firstInstance,
                        int instances) {
        var program = slice.get();
        program.bind();
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, batch.commandBuffer());
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, command);
        program.setUInt("sourceWord", (int) (batch.commandOffset() / Integer.BYTES));
        program.setUInt("firstPrimitive", firstPrimitive);
        program.setUInt("primitiveCount", primitives);
        program.setUInt("firstInstance", firstInstance);
        program.setUInt("instanceCount", instances);
        GL43C.glDispatchCompute(1, 1, 1);
        GL43C.glMemoryBarrier(GL43C.GL_COMMAND_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
    }

    private ProgramVersions<AsyncGlProgram[]> versions(Material material) {
        return programs.computeIfAbsent(key(material), _ -> new ProgramVersions<>(AsyncGlProgram.GROUP));
    }

    private static Key key(Material material) {
        return new Key(material.light(), material.shaders(), BackendConfig.INSTANCE.lightSmoothness());
    }

    private AsyncGlProgram[] compile(Key key) {
        var vertex = RenderPassShaders.farLayerMeshVertex(key.material(), source ->
                source.define("main", "_flw_farIrisMeshMain"));
        vertex.appendComponent(new RawSource("far Iris mesh identity", """
                #undef main
                flat out uint _flw_farIrisPart;
                void main() {
                    _flw_farIrisMeshMain();
                    uint row=_flw_instanceIndices[uint(gl_BaseInstanceARB)+uint(gl_InstanceID)];
                    _flw_farIrisPart=_flw_farRowPart(row);
                }
                """));
        var fragment = RenderPassShaders.farLayerIrisMeshFragment(key.light(), key.material(), key.smoothness(),
                ShaderAssembly.NO_EXTRA);
        fragment.appendComponent(new RawSource("far Iris mesh records", FarLayerCapture.RECORDS));
        fragment.appendComponent(new RawSource("far Iris mesh writer", """
                flat in uint _flw_farIrisPart;
                uniform uint _flw_farIrisWidth;
                uniform uint _flw_farIrisSourcePrimitive;
                uniform uint _flw_farIrisFlags;
                uniform vec3 _flw_farLayerCamera;
                void _flw_farCaptureIris(vec4 albedo,vec3 normal,vec4 tangent,vec2 light,float diffuse,
                        float unlit,uvec4 material,vec3 world) {
                    uint index=atomicAdd(_flw_farHitHeader.x,1u);
                    uint pixel=uint(gl_FragCoord.x)+uint(gl_FragCoord.y)*_flw_farIrisWidth;
                    _flw_farHits[index]=_FlwFarHit(albedo,vec4(normal,diffuse),vec4(light,unlit,1.0),material,
                            vec4(world-_flw_farLayerCamera,gl_FragCoord.z),
                            uvec4(_flw_farIrisSourcePrimitive+uint(gl_PrimitiveID)%_flw_farIrisMeshPrimitiveCount+1u,
                                    _flw_farIrisPart,
                                    _flw_farIrisFlags,pixel),tangent);
                }
                """));
        return new AsyncGlProgram[]{AsyncGlProgram.graphics("iris/far_mesh_capture", vertex, fragment,
                FarStore::setupTranslucentMeshProgram)};
    }

    private static void material(GlProgram program, Material material) {
        var mc = Minecraft.getInstance();
        texture(program, "Sampler0", 0,
                (GlTextureView) mc.getTextureManager().getTexture(material.texture()).getTextureView(),
                (GlSampler) MaterialSamplers.get(material));
        texture(program, "Sampler1", 1, (GlTextureView) mc.gameRenderer.overlayTexture().getTextureView(),
                (GlSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        texture(program, "Sampler2", 2, (GlTextureView) mc.gameRenderer.lightmap(),
                (GlSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }

    private static void texture(GlProgram program, String name, int unit, GlTextureView texture, GlSampler sampler) {
        program.setSamplerBinding(name, unit);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture.glId());
        GL33C.glBindSampler(unit, sampler.getId());
    }

    @Override
    public void close() {
        programs.values().forEach(ProgramVersions::release);
        programs.clear();
        slice.close();
        GL15C.glDeleteBuffers(command);
    }

    private record Key(LightShader light, MaterialShaders material, LightSmoothness smoothness) {
    }
}
