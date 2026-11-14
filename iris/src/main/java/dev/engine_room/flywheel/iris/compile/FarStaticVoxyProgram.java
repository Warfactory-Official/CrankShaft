package dev.engine_room.flywheel.iris.compile;

#if MC >= 26.3

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.engine.far.FarStaticDraw;
import dev.engine_room.flywheel.backend.engine.far.FarStaticMeshes;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.impl.compat.VoxyStaticVisuals;
import dev.engine_room.flywheel.impl.compat.LodCompat;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL33C;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Voxy's pack opaque callback applied directly to frozen section meshes.
 */
public final class FarStaticVoxyProgram implements AutoCloseable {
    private final FarStaticDraw draw = new FarStaticDraw(false);
    private final AsyncGlProgram program;
    private final boolean materialMaps;

    public FarStaticVoxyProgram(IrisVoxyRenderPipeline pipeline) {
        var data = pipeline._getData();
        var callback = FarVoxyMaterials.patch(FarVoxyNormals.patch(data.opaqueFragPatch()),
                data.getImageSet() == null ? "" : data.getImageSet().layout());
        materialMaps = callback.usesMaterialMaps();
        var vertex = new Compilation();
        vertex.version(GlslVersion.V460);
        var fragment = new Compilation();
        fragment.version(GlslVersion.V460);
        fragment.define("BUFFER_BINDING_INDEX_BASE", "10");
        fragment.define("BASE_SAMPLER_BINDING_INDEX", "6");
        if (materialMaps) {
            if (!GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
                throw new IllegalStateException("Static Voxy material maps require GL_ARB_bindless_texture");
            }
            fragment.requireExtension("GL_ARB_bindless_texture");
        }
        if (data.getUniforms() != null) {
            var uniforms = new RawSource("Voxy pack uniforms",
                    "layout(binding=7,std140) uniform ShaderUniformBindings " + data.getUniforms().layout() + ";");
            vertex.appendComponent(uniforms);
            fragment.appendComponent(uniforms);
        }
        if (data.getSsboSet() != null) {
            fragment.appendComponent(new RawSource("Voxy pack storage", data.getSsboSet().layout()));
        }
        if (data.getImageSet() != null) {
            fragment.appendComponent(new RawSource("Voxy pack samplers", data.getImageSet().layout()));
        }
        var jitter = new RawSource("Voxy static jitter", "vec2 _flw_farTaa() "
                + (data.TAA == null ? "{ return vec2(0.0); }" : data.TAA));
        vertex.appendComponent(jitter);
        FarStaticDraw.vertex(vertex, false, 0, 1, 2);
        vertex.appendComponent(new RawSource("static Voxy vertex", """
                uniform mat4 _flw_staticViewProjection;
                uniform vec2 _flw_staticDepthTransform;
                void main() {
                    _flw_staticInit();
                    gl_Position = _flw_staticViewProjection * vec4(_flw_staticWorld, 1.0);
                    gl_Position.xy += _flw_farTaa() * gl_Position.w;
                    gl_Position.z = gl_Position.z * _flw_staticDepthTransform.x
                            + gl_Position.w * _flw_staticDepthTransform.y;
                }
                """));
        fragment.appendComponent(new RawSource("static captured appearance", FarStaticDraw.FRAGMENT));
        fragment.appendComponent(new RawSource("static Voxy callback inputs", INPUTS));
        if (materialMaps) fragment.appendComponent(new RawSource("captured material maps", FarMaterialTextures.SHADER));
        fragment.appendComponent(new RawSource("Voxy opaque callback", callback.source()));
        fragment.appendComponent(new RawSource("static Voxy fragment", MAIN));
        program = AsyncGlProgram.graphics("far/static_voxy", vertex, fragment, _ -> {
        });
    }

    private void draw(FarStaticMeshes meshes, List<FarStaticDraw.DrawBatch> batches, int mapping, int clips,
                      IrisVoxyRenderPipeline pipeline, MDICViewport viewport, @Nullable RenderContext main) {
        var program = this.program.get();
        if (program == null) return;
        pipeline.setupAndBindOpaque(viewport);
        var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        // A render-scaled host (useViewportDims: SEUS GFME, Sundial/iterationRP SR) keeps main and Voxy content in
        // the viewport-sized lower-left region its targets share, so the main draw rasterizes over that region too.
        if (main != null) {
            draw.mainTarget(pipeline._getData().opaqueDrawTargets.length,
                    ((GlTexture) target.getDepthTexture()).glId(), viewport.width, viewport.height);
        }
        draw.geometry(meshes, mapping, clips, 0, 1, 2);
        GlStateManager._glUseProgram(program.handle());
        var camera = main == null ? new Vec3(viewport.cameraX, viewport.cameraY, viewport.cameraZ)
                : main.camera().position();
        FarStaticDraw.camera(program, camera);
        var mvp = main == null ? viewport.MVP : new Matrix4f(main.projection()).mul(main.modelView());
        program.setMat4("_flw_staticViewProjection", mvp);
        program.setMat4("_flw_staticModelView", main == null ? viewport.modelView : main.modelView());
        program.setMat4("_flw_staticHostProjection", viewport.projection);
        program.setMat4("_flw_staticVanillaProjection", viewport.vanillaProjection);
        program.setBool("_flw_staticMain", main != null);
        program.setFloat("_flw_staticMainRadius", main == null ? 0 : LodCompat.vanillaRadius());
        program.setVec2("_flw_staticMainDepthSize", viewport.width, viewport.height);
        program.setBool("_flw_staticZeroToOne", pipeline.properties.isZero2One());
        program.setBool("_flw_staticReverseZ", pipeline.properties.isReverseZ());
        boolean hardwareZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        boolean sourceZeroToOne = main != null || pipeline.properties.isZero2One();
        float scale = sourceZeroToOne == hardwareZeroToOne ? 1.0f : hardwareZeroToOne ? 0.5f : 2.0f;
        float offset = sourceZeroToOne == hardwareZeroToOne ? 0.0f : hardwareZeroToOne ? 0.5f : -1.0f;
        program.setVec2("_flw_staticDepthTransform", scale, offset);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE2);
        GlStateManager._bindTexture(main == null ? ((GlTexture) target.getDepthTexture()).glId()
                : pipeline.fb.getDepthTex().id);
        GL33C.glBindSampler(2, 0);
        program.setSamplerBinding("_flw_staticOccluderDepth", 2);
        if (materialMaps) FarMaterialTextures.bind();
        FarStaticDraw.bindOverlay(program, 1);
        GlStateManager._viewport(0, 0, viewport.width, viewport.height);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(main != null || pipeline.properties.isReverseZ() ? GL11C.GL_GEQUAL : GL11C.GL_LEQUAL);
        GlStateManager._depthMask(true);
        GlStateManager._disableScissorTest();
        for (int i = 0; i < pipeline._getData().opaqueDrawTargets.length; i++) {
            GlStateManager._disableBlend(i);
            GlStateManager._colorMask(i, 15);
        }
        for (var batch : batches) {
            draw.material(program, meshes.template(batch.templateId()), 0, 1);
            FarStaticDraw.indirect(batch);
        }
    }

    @Override
    public void close() {
        program.close();
        draw.close();
    }

    public static final class Provider implements VoxyStaticVisuals.DrawProvider {
        private final Map<AbstractRenderPipeline, FarStaticVoxyProgram> programs = new IdentityHashMap<>();

        @Override
        public boolean available(AbstractRenderPipeline pipeline) {
            return pipeline instanceof IrisVoxyRenderPipeline iris && GlCompat.SUPPORTS_INDIRECT
                    && programs.computeIfAbsent(pipeline, _ -> new FarStaticVoxyProgram(iris)).program.get() != null;
        }

        @Override
        public void draw(FarStaticMeshes meshes, List<FarStaticDraw.DrawBatch> batches, int mappingBuffer,
                         int clipBuffer, AbstractRenderPipeline pipeline, MDICViewport viewport) {
            programs.computeIfAbsent(pipeline, _ -> new FarStaticVoxyProgram((IrisVoxyRenderPipeline) pipeline))
                    .draw(meshes, batches, mappingBuffer, clipBuffer, (IrisVoxyRenderPipeline) pipeline, viewport,
                            null);
        }

        @Override
        public void drawMain(FarStaticMeshes meshes, List<FarStaticDraw.DrawBatch> batches, int mappingBuffer,
                             int clipBuffer, AbstractRenderPipeline pipeline, MDICViewport viewport,
                             RenderContext context) {
            programs.computeIfAbsent(pipeline, _ -> new FarStaticVoxyProgram((IrisVoxyRenderPipeline) pipeline))
                    .draw(meshes, batches, mappingBuffer, clipBuffer, (IrisVoxyRenderPipeline) pipeline, viewport,
                            context);
        }

        @Override
        public void close(AbstractRenderPipeline pipeline) {
            var program = programs.remove(pipeline);
            if (program != null) program.close();
        }
    }

    private static final String INPUTS = """
            uniform ivec3 _flw_staticCamera;
            uniform vec3 _flw_staticCameraFraction;
            uniform sampler2D _flw_staticOccluderDepth;
            uniform vec2 _flw_staticMainDepthSize;
            uniform mat4 _flw_staticModelView;
            uniform mat4 _flw_staticHostProjection;
            uniform mat4 _flw_staticVanillaProjection;
            uniform bool _flw_staticMain;
            uniform bool _flw_staticZeroToOne;
            uniform bool _flw_staticReverseZ;
            vec4 _flw_farFragCoord;
            vec3 _flw_farWorldNormal;
            vec4 _flw_farWorldTangent;
            struct VoxyFragmentParameters {
                vec4 sampledColour;
                vec2 tile;
                vec2 uv;
                uint face;
                uint modelId;
                vec2 lightMap;
                vec4 tinting;
                uint customId;
            };
            """;

    private static final String MAIN = """
            void main() {
                _flw_staticClip();
                vec4 albedo = _flw_staticSampleColor(_flw_staticColor);
                vec4 view = _flw_staticModelView * vec4(_flw_staticWorld, 1.0);
                vec4 hostClip = _flw_staticHostProjection * view;
                float hostDepth = hostClip.z / hostClip.w;
                if (!_flw_staticZeroToOne) hostDepth = hostDepth * 0.5 + 0.5;
                if (_flw_staticMain) {
                    // Invert Voxy's vanilla-mask UV mapping to locate this main-target pixel in opaque depth.
                    vec2 uv = gl_FragCoord.xy / _flw_staticMainDepthSize;
                    if (all(greaterThanEqual(uv, vec2(0.0))) && all(lessThan(uv, vec2(1.0)))) {
                        ivec2 texel = ivec2(uv * vec2(textureSize(_flw_staticOccluderDepth, 0)));
                        float occluder = texelFetch(_flw_staticOccluderDepth, texel, 0).r;
                        if (occluder > 0.0 && occluder < 1.0
                                && (_flw_staticReverseZ ? occluder > hostDepth : occluder < hostDepth)) discard;
                    }
                } else {
                    vec4 vanillaClip = _flw_staticVanillaProjection * view;
                    float depth = vanillaClip.z / vanillaClip.w;
                    if (!_flw_staticZeroToOne) depth = depth * 0.5 + 0.5;
                    vec2 uv = gl_FragCoord.xy / vec2(textureSize(_flw_staticOccluderDepth, 0));
                    float occluder = texture(_flw_staticOccluderDepth, uv).r;
                    if (_flw_staticReverseZ ? occluder > depth : occluder < depth) discard;
                }
                _flw_farFragCoord = vec4(gl_FragCoord.xy, hostDepth, 1.0 / hostClip.w);
                _flw_farWorldNormal = normalize(_flw_staticNormal);
                _flw_farWorldTangent = _flw_staticTangent();
                vec3 n = abs(_flw_farWorldNormal);
                uint face = n.y >= max(n.x, n.z) ? uint(_flw_farWorldNormal.y >= 0.0)
                        : n.z >= n.x ? 2u + uint(_flw_farWorldNormal.z >= 0.0)
                        : 4u + uint(_flw_farWorldNormal.x >= 0.0);
                vec3 world = _flw_staticWorld + _flw_staticCameraFraction;
                vec2 plane = (face >> 1u) == 0u ? world.xz : (face >> 1u) == 1u ? world.xy : world.zy;
                vec2 cameraPlane = vec2((face >> 1u) == 0u ? _flw_staticCamera.xz
                        : (face >> 1u) == 1u ? _flw_staticCamera.xy : _flw_staticCamera.zy);
                vec2 light = clamp(_flw_staticLight + 0.5 / 16.0, vec2(8.0 / 256.0), vec2(248.0 / 256.0));
                if ((_flw_staticFlags & 2u) == 0u) light = vec2(248.0 / 256.0);
                voxy_emitFragment(VoxyFragmentParameters(albedo, cameraPlane + floor(plane), _flw_staticUv,
                        face, 0u, light, vec4(1.0), _flw_staticMaterial & 65535u));
            }
            """;
}

#endif
