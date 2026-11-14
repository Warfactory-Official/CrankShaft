package dev.engine_room.flywheel.iris.compile;

#if MC >= 26.3

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.impl.compat.LodCompat;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.Minecraft;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

/**
 * Shades independently reconstructed layers into the same native pack targets as Voxy water.
 */
public final class FarLayerVoxyProgram implements AutoCloseable {
    private final AsyncGlProgram[] programs;
    private final @Nullable FarHostOit graph;
    private final FarLayerPixels pixels = new FarLayerPixels();
    private final boolean materialMaps;
    private final int vao = GL45C.glCreateVertexArrays();
    private final int mainFramebuffer = GL45C.glCreateFramebuffers();

    public FarLayerVoxyProgram(IrisVoxyRenderPipeline pipeline, @Nullable FarHostOit graph) {
        this.graph = graph;
        var data = pipeline._getData();
        var callback = FarVoxyMaterials.patch(FarVoxyNormals.patch(data.translucentFragPatch()),
                data.getImageSet() == null ? "" : data.getImageSet().layout());
        materialMaps = callback.usesMaterialMaps();
        var vertex = new Compilation();
        vertex.version(GlslVersion.V460);
        vertex.requireExtension("GL_ARB_shader_draw_parameters");
        vertex.appendComponent(new RawSource("layer records", FarLayerPixels.HITS));
        vertex.appendComponent(new RawSource("layer pixels", FarLayerPixels.VERTEX));
        var fragment = new Compilation();
        fragment.version(GlslVersion.V460);
        fragment.define("BUFFER_BINDING_INDEX_BASE", "10");
        fragment.define("BASE_SAMPLER_BINDING_INDEX", "6");
        if (materialMaps) {
            if (!GlCompat.SUPPORTS_BINDLESS_TEXTURES)
                throw new IllegalStateException("Voxy layered material maps require GL_ARB_bindless_texture");
            fragment.requireExtension("GL_ARB_bindless_texture");
        }
        if (data.getUniforms() != null) {
            var uniforms = new RawSource("Voxy pack uniforms",
                    "layout(binding=7,std140) uniform ShaderUniformBindings " + data.getUniforms().layout() + ";");
            fragment.appendComponent(uniforms);
            vertex.appendComponent(uniforms);
        }
        vertex.appendComponent(new RawSource("Voxy pack jitter", "vec2 _flw_farTaa() "
                + (data.TAA == null ? "{ return vec2(0.0); }" : data.TAA)));
        if (data.getSsboSet() != null)
            fragment.appendComponent(new RawSource("Voxy pack storage", data.getSsboSet().layout()));
        if (data.getImageSet() != null)
            fragment.appendComponent(new RawSource("Voxy pack samplers", data.getImageSet().layout()));
        fragment.appendComponent(new RawSource("layer records", FarLayerPixels.HITS));
        fragment.appendComponent(new RawSource("layer Voxy inputs", INPUTS));
        if (materialMaps)
            fragment.appendComponent(new RawSource("retained material maps", FarMaterialTextures.SHADER));
        fragment.appendComponent(new RawSource("Voxy translucent callback", callback.source()));
        fragment.appendComponent(new RawSource("layer Voxy shading", MAIN));
        programs = new AsyncGlProgram[graph == null ? 1 : graph.passCount()];
        for (int pass = 0; pass < programs.length; pass++) {
            if (graph == null) programs[pass] = AsyncGlProgram.graphics("iris/far_layer_voxy", vertex, fragment, _ -> {
            });
            else {
                var producer = new Compilation();
                producer.version(GlslVersion.V460);
                producer.appendNativeStage(new RawSource("Voxy retained shared producer",
                        graph.fragment(fragment.assembledSource(), pass, true)));
                programs[pass] = AsyncGlProgram.graphics("iris/far_layer_voxy/" + pass, vertex, producer,
                        graph::registerSamplers);
            }
        }
    }

    public boolean sharedGraphReady() {
        return graph != null && graph.ready();
    }

    public boolean ready() {
        boolean ready = pixels.ready();
        for (var program : programs) ready &= program.get() != null;
        return ready;
    }

    /**
     * After readiness; each window contains all records in its declared range. The caller supplies the target's
     * projection and depth convention. Main coverage complements host coverage at the vanilla radius.
     * The capture traversal owns the borrowed GL state scope across all windows.
     */
    public void draw(IrisVoxyRenderPipeline pipeline, Viewport<?> viewport, int hits, int maximumHits,
                     int width, int height, int opaqueDepth, Matrix4fc modelView, Matrix4fc targetProjection,
                     Matrix4fc occluderProjection, boolean zeroToOne, boolean reverseZ,
                     boolean occluderZeroToOne, boolean occluderReverseZ, boolean main, int mainDepth) {
        var shader = programs[graph == null ? 0 : graph.pass()].get();
        var data = pipeline._getData();
        pixels.prepare(hits, maximumHits);
        pipeline.setupAndBindTranslucent(viewport);
        GuestSsbos.bindAt((IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable(), 10);
        if (main && graph == null) {
            int[] buffers = new int[data.translucentDrawTargets.length];
            for (int i = 0; i < buffers.length; i++) {
                GL45C.glNamedFramebufferTexture(mainFramebuffer, GL30C.GL_COLOR_ATTACHMENT0 + i,
                        data.translucentDrawTargets[i], 0);
                buffers[i] = GL30C.GL_COLOR_ATTACHMENT0 + i;
            }
            GL45C.glNamedFramebufferTexture(mainFramebuffer, GL30C.GL_DEPTH_ATTACHMENT, mainDepth, 0);
            GL45C.glNamedFramebufferDrawBuffers(mainFramebuffer, buffers);
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, mainFramebuffer);
        }
        if (materialMaps) FarMaterialTextures.bind();
        shader.bind();
        if (graph != null) {
            graph.bindProgram(shader);
            shader.setMat4("_flw_farHostModelView", modelView);
        }
        shader.setVec2("_flw_farHitSize", width, height);
        var mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        shader.setVec2("_flw_farLayerMainOpaqueScale", (float) width / mainTarget.width,
                (float) height / mainTarget.height);
        shader.setMat4("_flw_farLayerModelView", modelView);
        shader.setMat4("_flw_farLayerProjection", targetProjection);
        shader.setMat4("_flw_farLayerOccluderProjection", occluderProjection);
        shader.setMat4("_flw_farLayerHostProjection", viewport.projection);
        shader.setBool("_flw_farLayerHostZeroToOne", pipeline.properties.isZero2One());
        shader.setBool("_flw_farLayerHostReverseZ", pipeline.properties.isReverseZ());
        shader.setBool("_flw_farLayerOccluderZeroToOne", occluderZeroToOne);
        shader.setBool("_flw_farLayerOccluderReverseZ", occluderReverseZ);
        shader.setBool("_flw_farLayerZeroToOne", zeroToOne);
        shader.setBool("_flw_farLayerMain", main);
        shader.setFloat("_flw_farLayerRadius", LodCompat.vanillaRadius());
        int cameraX = (int) Math.floor(viewport.cameraX);
        int cameraY = (int) Math.floor(viewport.cameraY);
        int cameraZ = (int) Math.floor(viewport.cameraZ);
        GL20C.glUniform3i(shader.getUniformLocation("_flw_farCameraWorld"),
                cameraX, cameraY, cameraZ);
        GL20C.glUniform3f(shader.getUniformLocation("_flw_farCameraFraction"),
                (float) (viewport.cameraX - cameraX), (float) (viewport.cameraY - cameraY),
                (float) (viewport.cameraZ - cameraZ));
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GlStateManager._bindTexture(opaqueDepth);
        GL33C.glBindSampler(0, 0);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 1);
        GlStateManager._bindTexture(pipeline.fb.getDepthTex().id);
        GL33C.glBindSampler(1, 0);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, FarLayerPixels.HIT_BINDING, hits);
        GlStateManager._glBindVertexArray(vao);
        GlStateManager._viewport(0, 0, width, height);
        if (graph == null) {
            // Voxy's native draw before an unblended host's sets this state raw; force it through Blaze3D's caches, as
            // FarHostOit's producer setup does for a graph.
            GlStateManager.SCISSOR.mode.enabled = true;
            GlStateManager.CULL.enable.enabled = true;
            GlStateManager.DEPTH.mode.enabled = false;
            GlStateManager.DEPTH.func = -1;
            GlStateManager.DEPTH.mask = false;
            for (int i = 0; i < data.translucentDrawTargets.length; i++) GlStateManager.COLOR_MASK[i] = -1;
        }
        if (graph == null || graph.deferred() && graph.pass() == 1) GlStateManager._enableDepthTest();
        else GlStateManager._disableDepthTest();
        GlStateManager._depthFunc((graph != null && graph.deferred() ? pipeline.properties.isReverseZ() : reverseZ)
                ? GL11C.GL_GEQUAL : GL11C.GL_LEQUAL);
        // Without a graph (an unblended host) the nearest surface wins, as in Voxy's native translucent draw.
        GlStateManager._depthMask(graph == null || graph.deferred() && graph.pass() == 1);
        GlStateManager._disableCull();
        GlStateManager._disableScissorTest();
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        for (int i = 0; i < data.translucentDrawTargets.length; i++)
            GlStateManager._colorMask(i, graph != null && graph.deferred() && graph.pass() == 0 ? 0 : 15);
        pixels.draw();
    }

    @Override
    public void close() {
        for (var program : programs) program.close();
        pixels.close();
        GL30C.glDeleteVertexArrays(vao);
        GL30C.glDeleteFramebuffers(mainFramebuffer);
    }

    private static final String INPUTS = """
            flat in uint _flw_farHitIndex;
            layout(binding=0) uniform sampler2D _flw_farLayerOpaqueDepth;
            layout(binding=1) uniform sampler2D _flw_farLayerHostOpaque;
            uniform mat4 _flw_farLayerModelView;
            uniform mat4 _flw_farLayerProjection;
            uniform mat4 _flw_farLayerOccluderProjection;
            uniform mat4 _flw_farLayerHostProjection;
            uniform bool _flw_farLayerHostZeroToOne;
            uniform bool _flw_farLayerHostReverseZ;
            uniform bool _flw_farLayerOccluderZeroToOne;
            uniform bool _flw_farLayerOccluderReverseZ;
            uniform vec2 _flw_farHitSize;
            uniform vec2 _flw_farLayerMainOpaqueScale;
            uniform bool _flw_farLayerZeroToOne;
            uniform bool _flw_farLayerMain;
            uniform float _flw_farLayerRadius;
            uniform ivec3 _flw_farCameraWorld;
            uniform vec3 _flw_farCameraFraction;
            vec3 _flw_farLayerCenter;
            vec4 _flw_farFragCoord;
            vec3 _flw_farWorldNormal;
            vec4 _flw_farWorldTangent;
            uvec4 _flw_farMaterial;
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
                _FlwFarHit hit = _flw_farHits[_flw_farHitIndex];
                _flw_farLayerCenter = hit.worldDepth.xyz;
                if ((length(hit.worldDepth.xz) <= _flw_farLayerRadius) != _flw_farLayerMain) discard;
                vec4 view = _flw_farLayerModelView * vec4(hit.worldDepth.xyz, 1.0);
                vec4 clip = _flw_farLayerProjection * view;
                float depth = clip.z / clip.w;
                if (!_flw_farLayerZeroToOne) depth = depth * 0.5 + 0.5;
                vec4 occluderClip = _flw_farLayerOccluderProjection * view;
                float occluderDepth = occluderClip.z / occluderClip.w;
                if (!_flw_farLayerOccluderZeroToOne) occluderDepth = occluderDepth * 0.5 + 0.5;
                float occluder = texture(_flw_farLayerOpaqueDepth, gl_FragCoord.xy / _flw_farHitSize * _flw_farLayerMainOpaqueScale).r;
                if (occluder != (_flw_farLayerOccluderReverseZ ? 0.0 : 1.0)
                        && (_flw_farLayerOccluderReverseZ ? occluder > occluderDepth : occluder < occluderDepth)) discard;
                gl_FragDepth = clamp(depth, 0.0, 1.0);
                vec4 hostClip = _flw_farLayerHostProjection * view;
                float hostDepth = hostClip.z / hostClip.w;
                if (!_flw_farLayerHostZeroToOne) hostDepth = hostDepth * 0.5 + 0.5;
                float hostOpaque = texture(_flw_farLayerHostOpaque, gl_FragCoord.xy / _flw_farHitSize).r;
                if (hostOpaque != (_flw_farLayerHostReverseZ ? 0.0 : 1.0)
                        && (_flw_farLayerHostReverseZ ? hostOpaque > hostDepth : hostOpaque < hostDepth)) discard;
                _flw_farFragCoord = vec4(gl_FragCoord.xy, hostDepth, 1.0 / hostClip.w);
                _flw_farWorldNormal = normalize(hit.normal.xyz);
                _flw_farMaterial = hit.material;
                _flw_farWorldTangent = hit.tangent;
                vec3 absoluteNormal = abs(_flw_farWorldNormal);
                uint face = absoluteNormal.y >= max(absoluteNormal.x, absoluteNormal.z)
                        ? uint(_flw_farWorldNormal.y >= 0.0)
                        : absoluteNormal.z >= absoluteNormal.x ? 2u + uint(_flw_farWorldNormal.z >= 0.0)
                        : 4u + uint(_flw_farWorldNormal.x >= 0.0);
                vec3 world = hit.worldDepth.xyz + _flw_farCameraFraction;
                vec2 plane = (face >> 1u) == 0u ? world.xz : (face >> 1u) == 1u ? world.xy : world.zy;
                vec2 cameraPlane = vec2((face >> 1u) == 0u ? _flw_farCameraWorld.xz
                        : (face >> 1u) == 1u ? _flw_farCameraWorld.xy : _flw_farCameraWorld.zy);
                vec2 lightMap = mix(clamp(hit.light.xy + vec2(0.5 / 16.0),
                        vec2(8.0 / 256.0), vec2(248.0 / 256.0)), vec2(248.0 / 256.0), hit.light.z);
                vec4 albedo = vec4(hit.color.rgb, hit.color.a * hit.light.w);
                voxy_emitFragment(VoxyFragmentParameters(albedo, cameraPlane + floor(plane),
                        uintBitsToFloat(hit.material.xy), face, 0u, lightMap, vec4(1.0), hit.material.z >> 16u));
            }
            """;
}

#endif
