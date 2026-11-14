package dev.engine_room.flywheel.iris.compile;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.MaterialEncoder;
import dev.engine_room.flywheel.backend.engine.RenderPassUniforms;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.impl.compat.LodCompat;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Vec3i;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Retained layers inside the vanilla radius as main content, shaded by the pack's main translucent program among the
 * guest translucents after all opaque geometry: in deferred capture, in each forward OIT producer pass, or in the plain
 * pass. A pack whose Voxy translucent targets are none of that program's targets hands them to main only where no main
 * opaque geometry was drawn ({@code depthtex1 == 1}: Sundial and SEUS GFME {@code deferred99}, Mellow
 * {@code deferred1}), which holds for its own LoD content beyond the radius but not for retained layers inside it.
 * The Voxy host pass keeps the records beyond the radius, so every record has one producer. Render thread.
 */
public final class FarLayerMainGuest {
    /**
     * Captured albedo and light carry the retained material: no texture, cutout or live light volume.
     */
    static final Material MATERIAL = SimpleMaterial.builder()
                                                   .transparency(Transparency.TRANSLUCENT)
                                                   .cutout(CutoutShaders.OFF)
                                                   .backfaceCulling(false)
                                                   .build();
    private static final int UBO_INSTANCE_DRAW = 11;
    private static final int ZERO_BYTES = 6 * 64;
    private static @Nullable FarLayerPixels pixels;
    private static @Nullable RenderPassUniforms uniforms;
    private static @Nullable GpuBuffer zero;
    private static @Nullable DynamicTexture white;
    private static @Nullable RenderContext uniformFrame;

    private FarLayerMainGuest() {
    }

    /**
     * Whether this frame's bridge has main-owned retained layers for the guest translucents.
     */
    public static boolean pending() {
        return FarLayerIrisBridge.mainGuestView() != null;
    }

    /**
     * After the guest draws of the same pass: deferred capture or the plain pass ({@code oit} null), or forward OIT
     * producer {@code oit}. {@code modelView}, {@code origin} and {@code constantAmbientLight} are the frame's guest
     * render values.
     */
    public static void draw(String label, @Nullable OitPass oit, Matrix4fc modelView, Vec3i origin,
                            boolean constantAmbientLight) {
        var view = FarLayerIrisBridge.mainGuestView();
        if (view == null) return;
        RenderPipeline pipeline = GuestPipelines.farLayer(oit);
        if (pipeline == null) return;
        var iris = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        var program = GuestPipelines.program(pipeline);
        prepare();
        if (uniformFrame != view.frame()) {
            uniformFrame = view.frame();
            uniforms.beginFrame(origin, constantAmbientLight);
        }
        var material = uniforms.material(MaterialEncoder.packProperties(MATERIAL));
        var camera = view.camera();
        float originX = (float) (camera.x - origin.getX());
        float originY = (float) (camera.y - origin.getY());
        float originZ = (float) (camera.z - origin.getZ());
        var minecraft = Minecraft.getInstance();
        var target = minecraft.gameRenderer.mainRenderTarget();
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        float radius = LodCompat.vanillaRadius();
        GuestProgram.setModelView(modelView);
        FarLayerIrisBridge.drawMainGuest(view.layers(), window -> {
            pixels.prepare(window.hits(), window.maximumHits());
            GuestSsbos.bindForGuest(iris);
            FarMaterialTextures.bind();
            var transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(modelView));
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label,
                    target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(),
                    OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setVertexBuffer(0, zero.slice());
                pass.setVertexBuffer(1, zero.slice());
                pass.setIndexBuffer(zero, IndexType.INT);
                pass.setUniform("Sampler0", white.getTextureView(), nearest);
                pass.setUniform("Sampler1", minecraft.gameRenderer.overlayTexture().getTextureView(), linear);
                pass.setUniform("Sampler2", minecraft.gameRenderer.lightmap(), linear);
                pass.setUniform("_FlwRenderOrigin", uniforms.renderOriginSlice());
                pass.setRenderPipeline(pipeline);
                pass.drawIndexed(0, 0, 0, 0, 0);
                bindUniform(material);
                program.setupFarLayer(view.projection(), view.inverseProjection(), view.viewToWorld(),
                        view.worldToView(), originX, originY, originZ, view.width(), view.height(), radius);
                GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, FarLayerPixels.HIT_BINDING, window.hits());
                pixels.draw();
            }
        });
    }

    private static void prepare() {
        if (pixels == null) pixels = new FarLayerPixels();
        if (uniforms == null) uniforms = new RenderPassUniforms();
        if (zero == null) {
            var bytes = MemoryUtil.memCalloc(ZERO_BYTES);
            try {
                zero = RenderSystem.getDevice().createBuffer(() -> "flywheel far layer zero vertices",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX, bytes);
            } finally {
                MemoryUtil.memFree(bytes);
            }
        }
        if (white == null) {
            var image = new NativeImage(1, 1, false);
            image.fillRect(0, 0, 1, 1, 0xffffffff);
            white = new DynamicTexture(() -> "flywheel far layer white", image);
        }
    }

    /**
     * Whether the compute grouping its records and every guest program {@code pipeline} draws them through are published.
     */
    static boolean ready(IrisRenderingPipeline pipeline) {
        prepare();
        return pixels.ready() && GuestPipelines.farLayerReady(pipeline);
    }

    private static void bindUniform(GpuBufferSlice slice) {
        GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, UBO_INSTANCE_DRAW, ((GlBuffer) slice.buffer()).handle(),
                slice.offset(), slice.length());
    }

    /**
     * Pipeline reload or device teardown.
     */
    static void reset() {
        if (pixels != null) pixels.close();
        if (zero != null) zero.close();
        if (white != null) white.close();
        pixels = null;
        zero = null;
        white = null;
    }

    /**
     * One record per pixel quad on its own plane, so the pack's vertex stage applies its own projection.
     */
    static void vertex(Compilation source) {
        source.appendComponent(new RawSource("far layer records", FarLayerPixels.HITS));
        source.appendComponent(new RawSource("far layer guest vertex", """
                layout(std430, binding = 25) readonly buffer _FlwFarHitMapping { uint _flw_farHitMapping[]; };
                uniform mat4 _flw_farLayerProjection;
                uniform mat4 _flw_farLayerInverseProjection;
                uniform mat4 _flw_farLayerViewToWorld;
                uniform mat4 _flw_farLayerWorldToView;
                uniform vec3 _flw_farLayerOrigin;
                uniform vec2 _flw_farHitSize;
                uniform float _flw_farLayerRadius;
                bool _flw_farLayerHost;
                flat out uint _flw_farTextureSlot;
                flat out vec4 _flw_farLayerAlbedo;
                flat out vec2 _flw_farLayerUv;
                // TODO: records carry no UV scale; this is a 16-texel sprite on a 1024-texel atlas.
                const float _flw_farLayerUvPerBlock = 1.0 / 64.0;
                vec2 _flw_farLayerCorner;
                vec3 _flw_farLayerView;
                const vec2 _flw_farLayerCorners[6] = vec2[6](
                        vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0),
                        vec2(0.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0));
                void _flw_guestVertex() {
                    _flw_guestTime(false);
                    _FlwFarHit hit = _flw_farHits[_flw_farHitMapping[uint(gl_BaseInstanceARB) + uint(gl_InstanceID)]];
                    // The Voxy host pass's own radius test.
                    _flw_farLayerHost = length(hit.worldDepth.xz) > _flw_farLayerRadius;
                    uint pixel = hit.address.w;
                    uvec2 at = uvec2(pixel % uint(_flw_farHitSize.x), pixel / uint(_flw_farHitSize.x));
                    vec2 ndc = (vec2(at) + _flw_farLayerCorners[gl_VertexID]) / _flw_farHitSize * 2.0 - 1.0;
                    vec3 normal = normalize(hit.normal.xyz);
                    vec3 center = (_flw_farLayerWorldToView * vec4(hit.worldDepth.xyz, 1.0)).xyz;
                    vec3 viewNormal = mat3(_flw_farLayerWorldToView) * normal;
                    vec4 nearPoint = _flw_farLayerInverseProjection * vec4(ndc, 0.25, 1.0);
                    vec4 farPoint = _flw_farLayerInverseProjection * vec4(ndc, 0.5, 1.0);
                    vec3 origin = nearPoint.xyz / nearPoint.w;
                    vec3 ray = farPoint.xyz / farPoint.w - origin;
                    vec3 corner = origin + ray * (dot(viewNormal, center - origin) / dot(viewNormal, ray));
                    _flw_farLayerCorner = ndc;
                    _flw_farLayerView = corner;
                    vec3 world = (_flw_farLayerViewToWorld * vec4(corner, 1.0)).xyz;
                    flw_vertexPos = vec4(world + _flw_farLayerOrigin, 1.0);
                    flw_vertexNormal = normal;
                    // The record's colour is the sampled albedo, tint included: an untinted vertex, as packs see a
                    // texture sample (a tinted vertex without a block id is water to Sundial).
                    _flw_farLayerAlbedo = vec4(hit.color.rgb, hit.color.a * hit.light.w);
                    flw_vertexColor = vec4(1.0);
                    // Packs derive their TBN from texture-coordinate derivatives (Sundial's calcTbnMatrix), which a
                    // per-record constant leaves 0/0: corners extend the record's UV along its tangent frame. Material
                    // maps read the record's own UV at their base level, as the Voxy host pass's records do.
                    _flw_farLayerUv = uintBitsToFloat(hit.material.xy);
                    vec3 offset = world - hit.worldDepth.xyz;
                    flw_vertexTexCoord = _flw_farLayerUv + vec2(dot(offset, hit.tangent.xyz),
                            dot(offset, cross(normal, hit.tangent.xyz) * hit.tangent.w)) * _flw_farLayerUvPerBlock;
                    flw_vertexOverlay = ivec2(0, 10);
                    flw_vertexLight = mix(hit.light.xy, _FLW_GUEST_UNLIT_LIGHT, hit.light.z);
                    _flw_farTextureSlot = hit.material.z & 65535u;
                    _flw_drawTag = 0u;
                    _flw_itemTag = 0u;
                    _flw_irisTangent = hit.tangent;
                    _flw_irisMidTexCoord = _flw_farLayerUv;
                    _flw_irisMidBlock = vec4(0.0);
                    #ifdef _FLW_GUEST_VELOCITY
                    _flw_guestMotion(flw_vertexPos);
                    #endif
                    #ifdef _FLW_GUEST_CONTRACT
                    _flw_packedMaterial = _flw_drawPackedMaterial;
                    clrwl_vertexTangent = _flw_guestTangent();
                    #endif
                    #ifdef _FLW_GUEST_OIT
                    _flw_oitViewZ = (flw_view * flw_vertexPos).z;
                    #endif
                }
                // After the pack's vertex stage. Corners recomputed per record differ in their last bits, so a pixel
                // centre on a shared edge could take both quads or neither: each corner takes its exact pixel corner
                // plus the pack's own offset (its TAA jitter), on a sixteenth-pixel grid that adjacent quads share.
                // Depth comes from the record's plane through the raw projection, so it is hardware depth whether or not
                // Iris remapped the pack's clip depth: its forward conversion (DepthTransformer, 0.5 * (w - z)) cancels
                // per corner to a coarse step, which a native triangle interpolates away but per-pixel quads turn into a
                // staircase.
                void _flw_farLayerRaster() {
                    if (_flw_farLayerHost) { gl_Position = vec4(0.0, 0.0, 2.0, 1.0); return; }
                    vec4 own = _flw_farLayerProjection * vec4(_flw_farLayerView, 1.0);
                    vec2 scale = _flw_farHitSize * 8.0;
                    vec2 offset = round((gl_Position.xy / gl_Position.w - own.xy / own.w) * scale) / scale;
                    gl_Position.xy = (_flw_farLayerCorner + offset) * gl_Position.w;
                    gl_Position.z = own.z / own.w * gl_Position.w;
                }
                """));
    }

    /**
     * After Iris's transform: albedo samples read the record's colour; its maps come from the retained material.
     */
    static String fragment(String source) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
                if (call.getFunctionName() != null && GuestShaders.ALBEDO_SAMPLES.contains(
                        call.getFunctionName().getName())
                        && !call.getParameters().isEmpty()
                        && call.getParameters().getFirst() instanceof ReferenceExpression sampler
                        && GuestShaders.ALBEDO.contains(sampler.getIdentifier().getName()))
                    call.replaceByAndDelete(parser.parseExpression(root, "_flw_farLayerAlbedo"));
            }
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "flat in vec4 _flw_farLayerAlbedo;");
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "flat in vec2 _flw_farLayerUv;");
            FarMaterialTextures.redirect(parser, tree, root, "_flw_farLayerUv");
        });
        return parser.transform(source);
    }
}
