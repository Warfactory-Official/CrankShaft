package dev.engine_room.flywheel.iris.compile;

#if MC >= 26.3

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.impl.far.FarSurface;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;
import org.jspecify.annotations.Nullable;


/**
 * One Voxy pipeline's opaque pack callback, shading the retained surface at its reconstructed depth.
 */
public final class FarVoxyProgram implements AutoCloseable {
    private static final int[] UNITS = {0, 1, 2, 3, 4, 5};
    private final boolean materialMaps;
    private final @Nullable FarMaterialPixels materialPixels;
    private final AsyncGlProgram program;
    private int vertexArray;
    private int sampler;
    private int inverseProjection;
    private int viewToWorld;
    private int hostProjection;
    private int vanillaProjection;
    private int cameraWorld;
    private int cameraFraction;
    private int hostSize;
    private int zeroToOne;
    private int reverseZ;

    public FarVoxyProgram(IrisVoxyRenderPipeline pipeline) {
        IrisVoxyRenderPipelineData data = pipeline._getData();
        var vertex = new Compilation();
        vertex.version(GlslVersion.V460);
        var fragment = new Compilation();
        fragment.version(GlslVersion.V460);
        var callback = FarVoxyMaterials.patch(FarVoxyNormals.patch(data.opaqueFragPatch()),
                data.getImageSet() == null ? "" : data.getImageSet().layout());
        materialMaps = callback.usesMaterialMaps();
        if (materialMaps) {
            if (!GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
                throw new IllegalStateException("Voxy retained material-map sampling requires GL_ARB_bindless_texture");
            }
            fragment.requireExtension("GL_ARB_bindless_texture");
            vertex.requireExtension("GL_ARB_bindless_texture");
            fragment.define("_FLW_FAR_MATERIAL_MAPS");
            vertex.requireExtension("GL_ARB_shader_draw_parameters");
        }
        fragment.define("BUFFER_BINDING_INDEX_BASE", "10");
        fragment.define("BASE_SAMPLER_BINDING_INDEX", "6");
        if (data.getUniforms() != null) {
            var uniforms = new RawSource("Voxy pack uniforms",
                    "layout(binding=7,std140) uniform ShaderUniformBindings " + data.getUniforms().layout() + ";");
            fragment.appendComponent(uniforms);
            if (materialMaps) vertex.appendComponent(uniforms);
        }
        if (data.getSsboSet() != null)
            fragment.appendComponent(new RawSource("Voxy pack storage", data.getSsboSet().layout()));
        if (data.getImageSet() != null)
            fragment.appendComponent(new RawSource("Voxy pack samplers", data.getImageSet().layout()));
        fragment.appendComponent(new RawSource("Voxy far inputs", INPUTS));
        if (materialMaps)
            fragment.appendComponent(new RawSource("Voxy retained material textures", FarMaterialTextures.SHADER));
        var jitter = new RawSource("Voxy pack jitter", "vec2 _flw_farTaa() "
                + (data.TAA == null ? "{ return vec2(0.0); }" : data.TAA));
        fragment.appendComponent(jitter);
        if (materialMaps) {
            vertex.appendComponent(jitter);
            vertex.appendComponent(new RawSource("Voxy retained material pixels", FarMaterialPixels.VERTEX));
        } else {
            vertex.appendComponent(new RawSource("far Voxy fullscreen", """
                    void main() {
                        vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
                    }
                    """));
        }
        fragment.appendComponent(new RawSource("Voxy opaque pack callback", callback.source()));
        fragment.appendComponent(new RawSource("Voxy far shading", MAIN));
        materialPixels = materialMaps ? new FarMaterialPixels() : null;
        program = AsyncGlProgram.graphics("far/voxy", vertex, fragment, published -> {
            vertexArray = GL45C.glCreateVertexArrays();
            sampler = GL45C.glCreateSamplers();
            GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            inverseProjection = published.getUniformLocation("_flw_farInverseProjection");
            viewToWorld = published.getUniformLocation("_flw_farViewToWorld");
            hostProjection = published.getUniformLocation("_flw_farHostProjection");
            vanillaProjection = published.getUniformLocation("_flw_farVanillaProjection");
            cameraWorld = published.getUniformLocation("_flw_farCameraWorld");
            cameraFraction = published.getUniformLocation("_flw_farCameraFraction");
            hostSize = published.getUniformLocation("_flw_farHostSize");
            zeroToOne = published.getUniformLocation("_flw_farZeroToOne");
            reverseZ = published.getUniformLocation("_flw_farReverseZ");
        });
    }

    public void draw(IrisVoxyRenderPipeline pipeline, Viewport<?> viewport, int sourceDepth, FarSurface surface) {
        var program = this.program.get();
        if (program == null || materialMaps && !materialPixels.ready()) return;
        GlCompat.pushDebugGroup("far/voxy");
        try (var saved = FarIrisState.capture(UNITS, pipeline._getData().opaqueDrawTargets.length,
                materialMaps ? new int[]{0, 1, 2, FarMaterialTextures.BINDING} : new int[0]);
             var stack = MemoryStack.stackPush()) {
            pipeline.setupAndBindOpaque(viewport);
            GlStateManager._glBindVertexArray(vertexArray);
            if (materialMaps) FarMaterialTextures.bind();
            int[] textures = {surface.material(), sourceDepth, surface.color(), surface.normal(), surface.light(), surface.depth()};
            for (int i = 0; i < UNITS.length; i++) {
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + UNITS[i]);
                GlStateManager._bindTexture(textures[i]);
                GL33C.glBindSampler(UNITS[i], sampler);
            }
            if (materialMaps) {
                materialPixels.prepare(surface);
                materialPixels.bind();
            }
            program.bind();
            GL20C.glUniformMatrix4fv(inverseProjection, false, surface.inverseProjection().get(stack.mallocFloat(16)));
            GL20C.glUniformMatrix4fv(viewToWorld, false, surface.viewToWorld().get(stack.mallocFloat(16)));
            GL20C.glUniformMatrix4fv(hostProjection, false, viewport.projection.get(stack.mallocFloat(16)));
            GL20C.glUniformMatrix4fv(vanillaProjection, false, viewport.vanillaProjection.get(stack.mallocFloat(16)));
            int cameraX = (int) Math.floor(viewport.cameraX);
            int cameraY = (int) Math.floor(viewport.cameraY);
            int cameraZ = (int) Math.floor(viewport.cameraZ);
            GL20C.glUniform3i(cameraWorld, cameraX, cameraY, cameraZ);
            GL20C.glUniform3f(cameraFraction, (float) (viewport.cameraX - cameraX),
                    (float) (viewport.cameraY - cameraY), (float) (viewport.cameraZ - cameraZ));
            GL20C.glUniform2f(hostSize, viewport.width, viewport.height);
            GL20C.glUniform1i(zeroToOne, pipeline.properties.isZero2One() ? 1 : 0);
            GL20C.glUniform1i(reverseZ, pipeline.properties.isReverseZ() ? 1 : 0);
            GL11C.glViewport(0, 0, viewport.width, viewport.height);
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(pipeline.properties.closerEqualDepthCompare());
            GlStateManager._depthMask(true);
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            GlStateManager._disableScissorTest();
            GlStateManager._disableCull();
            for (int target = 0; target < pipeline._getData().opaqueDrawTargets.length; target++) {
                GlStateManager._disableBlend(target);
                GlStateManager._colorMask(target, 15);
            }
            if (materialMaps) materialPixels.draw();
            else GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    @Override
    public void close() {
        program.close();
        GL30C.glDeleteVertexArrays(vertexArray);
        GL33C.glDeleteSamplers(sampler);
        if (materialMaps) materialPixels.close();
    }

    private static final String INPUTS = """
            layout(binding=0) uniform usampler2D _flw_farMaterials;
            layout(binding=1) uniform sampler2D _flw_farVanillaDepth;
            layout(binding=2) uniform sampler2D _flw_farColor;
            layout(binding=3) uniform sampler2D _flw_farNormal;
            layout(binding=4) uniform sampler2D _flw_farLight;
            layout(binding=5) uniform sampler2D _flw_farDepth;
            uniform mat4 _flw_farInverseProjection;
            uniform mat4 _flw_farViewToWorld;
            uniform mat4 _flw_farHostProjection;
            uniform mat4 _flw_farVanillaProjection;
            uniform ivec3 _flw_farCameraWorld;
            uniform vec3 _flw_farCameraFraction;
            uniform vec2 _flw_farHostSize;
            uniform bool _flw_farZeroToOne;
            uniform bool _flw_farReverseZ;
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
                vec2 screenUv = gl_FragCoord.xy / _flw_farHostSize;
                vec2 uv = screenUv - 0.5 * _flw_farTaa();
                if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) discard;
                float depth = texture(_flw_farDepth, uv).r;
                if (depth == 0.0) discard;
                vec4 view = _flw_farInverseProjection * vec4(uv * 2.0 - 1.0, depth, 1.0);
                view /= view.w;
                vec4 hostClip = _flw_farHostProjection * view;
                float hostDepth = hostClip.z / hostClip.w;
                if (!_flw_farZeroToOne) hostDepth = 0.5 * hostDepth + 0.5;
                vec4 vanillaClip = _flw_farVanillaProjection * view;
                float vanillaDepth = vanillaClip.z / vanillaClip.w;
                if (!_flw_farZeroToOne) vanillaDepth = 0.5 * vanillaDepth + 0.5;
                float occluder = texture(_flw_farVanillaDepth, screenUv).r;
                if (_flw_farReverseZ ? occluder > vanillaDepth : occluder < vanillaDepth) discard;
                gl_FragDepth = clamp(hostDepth, 0.0, 1.0);
                _flw_farFragCoord = vec4(gl_FragCoord.xy, gl_FragDepth, 1.0 / hostClip.w);
                _flw_farWorldNormal = normalize(texture(_flw_farNormal, uv).rgb * 2.0 - 1.0);
                _flw_farMaterial = texture(_flw_farMaterials, uv);
                _flw_farWorldTangent = vec4(unpackSnorm4x8(_flw_farMaterial.w).xyz,
                        (_flw_farMaterial.w & 0x01000000u) != 0u ? -1.0 : 1.0);
                vec3 absoluteNormal = abs(_flw_farWorldNormal);
                uint face = absoluteNormal.y >= max(absoluteNormal.x, absoluteNormal.z)
                        ? uint(_flw_farWorldNormal.y >= 0.0)
                        : absoluteNormal.z >= absoluteNormal.x ? 2u + uint(_flw_farWorldNormal.z >= 0.0)
                        : 4u + uint(_flw_farWorldNormal.x >= 0.0);
                vec3 world = (_flw_farViewToWorld * view).xyz + _flw_farCameraFraction;
                vec2 plane = (face >> 1u) == 0u ? world.xz : (face >> 1u) == 1u ? world.xy : world.zy;
                vec2 cameraPlane = vec2((face >> 1u) == 0u ? _flw_farCameraWorld.xz
                        : (face >> 1u) == 1u ? _flw_farCameraWorld.xy : _flw_farCameraWorld.zy);
                vec3 light = texture(_flw_farLight, uv).xyz;
                vec2 lightMap = clamp(light.xy + vec2(0.5 / 16.0), vec2(8.0 / 256.0), vec2(248.0 / 256.0));
                lightMap = mix(lightMap, vec2(248.0 / 256.0), light.z);
                vec4 albedo = vec4(texture(_flw_farColor, uv).rgb, 1.0);
                #ifdef _FLW_FAR_MATERIAL_MAPS
                vec2 materialUv = uintBitsToFloat(_flw_farMaterial.xy);
                #else
                vec2 materialUv = fract(plane);
                #endif
                voxy_emitFragment(VoxyFragmentParameters(albedo, cameraPlane + floor(plane), materialUv, face, 0u,
                        lightMap, vec4(1.0), _flw_farMaterial.z >> 16u));
            }
            """;
}

#endif
