package dev.engine_room.flywheel.impl.far;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import dev.engine_room.flywheel.api.material.LightShader;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.MaterialShaders;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.compile.*;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.MaterialSamplers;
import dev.engine_room.flywheel.backend.engine.far.FarLayerView;
import dev.engine_room.flywheel.backend.engine.far.FarStore;
import dev.engine_room.flywheel.backend.engine.indirect.RawOitPass;
import dev.engine_room.flywheel.backend.engine.indirect.RawOitShaders;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Native shading of each retained hit, before the selected shared transparency resolve. Render thread.
 */
final class FarNativeLayers implements AutoCloseable {
    private static final OitMode[] WAVELET = {OitMode.DEPTH_RANGE, OitMode.GENERATE_COEFFICIENTS, OitMode.EVALUATE};
    private final Map<MeshKey, ProgramVersions<AsyncGlProgram[]>> meshes = new HashMap<>();
    private final Map<Output, AsyncGlProgram[]> boxes = new HashMap<>();
    private @Nullable ShaderSources sources;

    boolean ready(FarStore store, @Nullable OitInsertMode mode) {
        return ready(store, mode, false);
    }

    boolean readyForBake(FarStore store, @Nullable OitInsertMode mode) {
        return ready(store, mode, true);
    }

    private boolean ready(FarStore store, @Nullable OitInsertMode mode, boolean complete) {
        if (sources != FlwPrograms.SOURCES) {
            close();
            sources = FlwPrograms.SOURCES;
        }
        var output = new Output(mode);
        var box = boxes.computeIfAbsent(output, this::boxPrograms);
        boolean[] ready = {AsyncGlProgram.GROUP.state(box) == ProgramVersions.State.PUBLISHED};
        store.forEachTranslucentMaterial(material -> {
            var versions = meshVersions(material, output);
            var programs = versions.select(ProgramGeneration.current(), key(material, output), this::meshPrograms);
            if (complete) ready[0] &= programs != null && versions.generation().covers(ProgramGeneration.current());
        });
        return ready[0];
    }

    void draw(FarStore store, FarLayerView view, RawOitPass pass, float radius, boolean main) {
        var output = new Output(pass.insert());
        int stage = pass.insert() != null ? 0 : pass.wavelet().ordinal() - 1;
        store.forEachTranslucentMesh(batch -> {
            var versions = meshVersions(batch.material(), output);
            var programs = versions.select(ProgramGeneration.current(), key(batch.material(), output),
                    this::meshPrograms);
            if (programs == null || !versions.generation().covers(batch.required())) return;
            GlProgram program = programs[stage].get();
            pass.bind();
            program.bind();
            store.bindTranslucentMesh(program, batch, view);
            bind(program, view, pass, radius, main);
            bindMaterial(program, batch.material());
            if (batch.material().backfaceCulling()) GlStateManager._enableCull();
            else GlStateManager._disableCull();
            GL43C.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT,
                    batch.commandOffset(), batch.drawCount(), 48);
        });
        var builds = boxes.get(output);
        if (builds == null || AsyncGlProgram.GROUP.state(builds) != ProgramVersions.State.PUBLISHED) return;
        pass.bind();
        GlProgram program = builds[stage].get();
        program.bind();
        bind(program, view, pass, radius, main);
        store.drawLayeredBoxes(program, view);
    }

    private ProgramVersions<AsyncGlProgram[]> meshVersions(Material material, Output output) {
        return meshes.computeIfAbsent(key(material, output), _ -> new ProgramVersions<>(AsyncGlProgram.GROUP));
    }

    private static MeshKey key(Material material, Output output) {
        return new MeshKey(material.light(), material.shaders(), BackendConfig.INSTANCE.lightSmoothness(), output);
    }

    private AsyncGlProgram[] meshPrograms(MeshKey key) {
        var programs = new AsyncGlProgram[key.output.insert == null ? 3 : 1];
        for (int i = 0; i < programs.length; i++) {
            var vertex = RenderPassShaders.farLayerMeshVertex(key.material, ShaderAssembly.NO_EXTRA);
            var fragment = RenderPassShaders.farLayerMeshFragment(key.light, key.material, key.smoothness,
                    ShaderAssembly.NO_EXTRA);
            RawOitShaders.appendProducer(fragment, key.output.insert == null ? WAVELET[i] : null,
                    key.output.insert, true);
            fragment.appendComponent(new RawSource("far native acceptance", KEEP));
            programs[i] = AsyncGlProgram.graphics("far/native_mesh/" + key.output + "/" + i,
                    vertex, fragment, FarStore::setupTranslucentMeshProgram);
        }
        return programs;
    }

    private AsyncGlProgram[] boxPrograms(Output output) {
        var programs = new AsyncGlProgram[output.insert == null ? 3 : 1];
        for (int i = 0; i < programs.length; i++) {
            var vertex = new Compilation();
            vertex.version(GlslVersion.V430);
            vertex.define("_FLW_FAR_NO_ROWS");
            RawOitShaders.append(vertex, "internal/far/layer_box.vert");
            var fragment = new Compilation();
            fragment.version(GlslVersion.V430);
            fragment.define("_FLW_FAR_NO_ROWS");
            RawOitShaders.append(fragment, "internal/far/layer_sample.glsl");
            RawOitShaders.appendProducer(fragment, output.insert == null ? WAVELET[i] : null, output.insert, false);
            fragment.appendComponent(new RawSource("far native acceptance", KEEP));
            fragment.appendComponent(new RawSource("far native layered shading", """
                    flat in uint _flw_farLayerPartSlot;
                    in vec3 _flw_farLayerWorld;
                    uniform sampler2D Sampler2;
                    void main() {
                        FarLayerRay ray=_flw_farBeginRay(_flw_farLayerPartSlot,_flw_farLayerCamera,
                                _flw_farLayerWorld-_flw_farLayerCamera);
                        _flw_rawBegin();
                        FarLayerHit hit;
                        _flw_rawLock();
                        while(_flw_farNextHit(ray,hit)) {
                            if(!_flw_farRawKeep(hit.positionDepth.xyz,hit.positionDepth.w)) continue;
                            vec2 uv=clamp(hit.lightCoverage.xy+0.5/16.0,vec2(0.5/16.0),vec2(15.5/16.0));
                            vec3 light=mix(texture(Sampler2,uv).rgb,vec3(1.0),hit.lightCoverage.z);
                            vec4 color=vec4(hit.albedo.rgb*light*hit.normalDiffuse.w,
                                    hit.albedo.a*hit.lightCoverage.w);
                            _flw_rawEmit(color,_flw_rawLinearDepth(hit.positionDepth.w),
                                    _flw_rawDeviceDepth(hit.positionDepth.w));
                        }
                        _flw_rawUnlock();
                    }
                    """));
            programs[i] = AsyncGlProgram.graphics("far/native_layers/" + output + "/" + i,
                    vertex, fragment, _ -> {
                    });
        }
        return programs;
    }

    private static void bind(GlProgram program, FarLayerView view, RawOitPass pass, float radius, boolean main) {
        RawOitShaders.bind(program, pass.target());
        program.setVec3("_flw_farRawCamera", (float) (view.camera().x - view.renderOrigin().getX()),
                (float) (view.camera().y - view.renderOrigin().getY()),
                (float) (view.camera().z - view.renderOrigin().getZ()));
        program.setFloat("_flw_farRawRadius", radius);
        program.setBool("_flw_farRawMain", main);
        program.setSamplerBinding("_flw_farRawOpaque", 16);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 16);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, pass.opaqueDepth());
        GL33C.glBindSampler(16, ((GlSampler) RenderSystem.getSamplerCache()
                                                         .getClampToEdge(FilterMode.NEAREST)).getId());
        texture(program, "Sampler2", 2, (GlTextureView) Minecraft.getInstance().gameRenderer.lightmap(),
                (GlSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }

    private static void bindMaterial(GlProgram program, Material material) {
        var mc = Minecraft.getInstance();
        texture(program, "Sampler0", 0,
                (GlTextureView) mc.getTextureManager().getTexture(material.texture()).getTextureView(),
                (GlSampler) MaterialSamplers.get(material));
        texture(program, "Sampler1", 1, (GlTextureView) mc.gameRenderer.overlayTexture().getTextureView(),
                (GlSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }

    private static void texture(GlProgram program, String name, int unit, GlTextureView view, GlSampler sampler) {
        program.setSamplerBinding(name, unit);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(view.glId());
        GL33C.glBindSampler(unit, sampler.getId());
    }

    @Override
    public void close() {
        meshes.values().forEach(ProgramVersions::release);
        boxes.values().forEach(AsyncGlProgram.GROUP::release);
        meshes.clear();
        boxes.clear();
        sources = null;
    }

    private record Output(@Nullable OitInsertMode insert) {
    }

    private record MeshKey(LightShader light, MaterialShaders material, LightSmoothness smoothness, Output output) {
    }

    private static final String KEEP = """
            uniform sampler2D _flw_farRawOpaque;
            uniform vec3 _flw_farRawCamera;
            uniform float _flw_farRawRadius;
            uniform bool _flw_farRawMain;
            bool _flw_farRawKeep(vec3 position,float depth) {
                vec2 delta=position.xz-_flw_farRawCamera.xz;
                bool inside=dot(delta,delta)<_flw_farRawRadius*_flw_farRawRadius;
                if(inside!=_flw_farRawMain) return false;
                float opaque=texelFetch(_flw_farRawOpaque,ivec2(gl_FragCoord.xy),0).r;
                return _flw_rawReverseZ?depth>=opaque:depth<=opaque;
            }
            """;
}
