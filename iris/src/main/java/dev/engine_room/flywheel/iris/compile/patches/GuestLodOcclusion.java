package dev.engine_room.flywheel.iris.compile.patches;

#if MC < 26.3
import dev.engine_room.flywheel.backend.gl.GlCompat;
#endif
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsCompat;
import dev.engine_room.flywheel.impl.compat.VoxyCompat;
import dev.engine_room.flywheel.iris.IrisRuntime;
import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import net.irisshaders.iris.compat.dh.DHCompatInternal;
#if MC < 26.3
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
#endif
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
#if MC < 26.3
import net.irisshaders.iris.targets.DepthTexture;
#endif
import org.jspecify.annotations.Nullable;
#if MC < 26.3
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;
#endif

import java.util.HashMap;
import java.util.Map;

/**
 * DH terrain hides guest instance fragments past the vanilla terrain radius, as the shaders-off LoD depth pass does.
 * Voxy tests every pixel, including holes within that conservative radius, and ignores its vanilla-coverage endpoint
 * depths. LoD water never hides: guest translucents behind it draw and the pack composites the water over them.
 * Applied to the pack fragment before Iris transforms it, the test reads the LoD depth and projection in the pack's
 * own convention and runs before any output or capture.
 */
public final class GuestLodOcclusion {
    /**
     * Horizontal vanilla terrain radius in blocks; the program's binder supplies it.
     */
    public static final String RADIUS = "_flw_lodRadius";
    #if MC < 26.3
    /**
     * LoD terrain depth sampler; the program's binder supplies {@link #terrainDepth()}.
     */
    public static final String TERRAIN_DEPTH = "_flw_lodTerrainDepth";
    // glCopyImageSubData: DH's depth and the copy are both DEPTH32F (as Iris's own dhDepthTex1 copy).
    private static final boolean COPY = GlCompat.CAPABILITIES != null
            && GlCompat.CAPABILITIES.glCopyImageSubData != MemoryUtil.NULL
            && GlCompat.CAPABILITIES.glGetTextureLevelParameteriv != MemoryUtil.NULL;
    #endif
    private static final String TEST = "_flw_lodOccluded";
    private static final String DH_RADIUS_TEST = """
                vec4 ray = gbufferProjectionInverse * vec4(ndc, %s, 1.0);
                float horizontal = length((mat3(gbufferModelViewInverse) * (ray.xyz / -ray.z)).xz);
                if (depth * horizontal <= _flw_lodRadius) return false;
            """;
    // Depth along the view axis from gl_FragCoord.w: exact where the instance's window depth has no precision left.
    private static final String FUNCTION = """
            bool _flw_lodOccluded() {
                vec2 screen = vec2(viewWidth, viewHeight);
                vec2 ndc = gl_FragCoord.xy / screen * 2.0 - 1.0;
                float depth = 1.0 / gl_FragCoord.w;
            %4$s
                ivec2 texel = ivec2(gl_FragCoord.xy / screen * vec2(textureSize(%1$s, 0)));
                float lod = %3$stexelFetch(%1$s, texel, 0).r;
                if (%5$s) return false;
                vec4 view = %2$s * vec4(ndc, %6$s, 1.0);
                return depth > -view.z / view.w;
            }
            """;
    private static final Map<Key, String> APPLIED = new HashMap<>();
    #if MC < 26.3
    private static @Nullable DepthTexture terrain;
    private static int terrainWidth, terrainHeight;
    #endif

    private GuestLodOcclusion() {
    }

    /**
     * The LoD mod drawing into this pipeline's targets, or {@code null}. Both mods reload the pipeline when their
     * rendering toggles.
     */
    public static @Nullable Source source(IrisRenderingPipeline pipeline) {
        // TODO: experimental Iris: no LoD lanes, and its Vulkan hosts admit no loose radius uniform.
        if (IrisRuntime.EXPERIMENTAL) return null;
        if (DistantHorizonsCompat.ACTIVE && pipeline.getDHCompat().getInstance() instanceof DHCompatInternal dh
                && dh.shouldOverride) {
            #if MC < 26.3
            return COPY ? Source.DISTANT_HORIZONS : null;
            #else
            return Source.DISTANT_HORIZONS;
            #endif
        }
        return VoxyCompat.drawsInto(pipeline) ? Source.VOXY : null;
    }

    #if MC < 26.3
    /**
     * Main pass, render thread, before guest draws: DH's depth holds this frame's opaque LoDs and none of its deferred
     * translucents yet (they follow at {@code renderGroup(TRANSLUCENT)}, before the guest translucent seam).
     */
    public static void captureTerrainDepth(IrisRenderingPipeline pipeline) {
        if (source(pipeline) != Source.DISTANT_HORIZONS) return;
        int depth = pipeline.getDHCompat().getDepthTex();
        if (depth <= 0) return;
        int width = GL45C.glGetTextureLevelParameteri(depth, 0, GL11C.GL_TEXTURE_WIDTH);
        int height = GL45C.glGetTextureLevelParameteri(depth, 0, GL11C.GL_TEXTURE_HEIGHT);
        if (terrain == null || width != terrainWidth || height != terrainHeight) {
            releaseTerrainDepth();
            terrain = new DepthTexture("flywheel:lod_terrain_depth", width, height, DepthBufferFormat.DEPTH32F);
            // Minecraft's no-mipmap sampler still has a mipmap min filter; this copy defines only level zero.
            GL45C.glTextureParameteri(terrain.getTextureId(), GL12C.GL_TEXTURE_MAX_LEVEL, 0);
            terrainWidth = width;
            terrainHeight = height;
        }
        IrisRenderSystem.copyImageSubData(depth, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, terrain.getTextureId(),
                GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
    }

    public static int terrainDepth() {
        return terrain == null ? 0 : terrain.getTextureId();
    }

    /**
     * Iris pipeline destruction, render thread.
     */
    public static void releaseTerrainDepth() {
        if (terrain != null) {
            terrain.destroy();
            terrain = null;
        }
    }
    #endif

    /**
     * Render thread; {@link GuestShaders} clears the memo with its own. {@code reversed}: the pack takes raw reversed
     * depth and projections, the DH ones zero-to-one and Voxy's in the device's clip range.
     */
    public static String apply(String fragment, Source source, boolean reversed) {
        return APPLIED.computeIfAbsent(new Key(fragment, source, reversed), key -> {
            var parser = GuestShaders.versionedTransformer();
            parser.setTransformation((tree, root) -> {
                ShaderDeclarations.uniform(tree, root, parser, "float", "viewWidth");
                ShaderDeclarations.uniform(tree, root, parser, "float", "viewHeight");
                if (source != Source.VOXY) {
                    ShaderDeclarations.uniform(tree, root, parser, "mat4", "gbufferProjectionInverse");
                    ShaderDeclarations.uniform(tree, root, parser, "mat4", "gbufferModelViewInverse");
                    ShaderDeclarations.uniform(tree, root, parser, "float", RADIUS);
                }
                ShaderDeclarations.uniform(tree, root, parser, "sampler2D", source.depth);
                ShaderDeclarations.uniform(tree, root, parser, "mat4", source.projectionInverse);
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "bool " + TEST + "();");
                boolean voxy = source == Source.VOXY;
                String lodNdc = !reversed || voxy && !RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()
                        ? "lod * 2.0 - 1.0" : "lod";
                tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                        FUNCTION.formatted(source.depth, source.projectionInverse,
                                source.raw && !reversed ? "1.0 - " : "",
                                voxy ? "" : DH_RADIUS_TEST.formatted(reversed ? "1.0" : "-1.0"),
                                voxy ? "lod <= 0.0 || lod >= 1.0" : reversed ? "lod <= 0.0" : "lod >= 1.0", lodNdc));
                tree.prependMainFunctionBody(parser, "if (" + TEST + "()) discard;");
            });
            return parser.transform(key.fragment());
        });
    }

    public static void clearMemo() {
        APPLIED.clear();
    }

    // LoD terrain depth, current at both guest seams. DH's opaque-only dhDepthTex1 is copied when its deferred
    // translucents start, after guest opaque draws: the previous frame's there.
    public enum Source {
        #if MC < 26.3
        // 26.2: a raw copy; Iris inverts reads of its own depth samplers only.
        DISTANT_HORIZONS(TERRAIN_DEPTH, "dhProjectionInverse", true),
        #else
        // 26.3: DH's deferred translucents follow at executeOutline, after both seams.
        DISTANT_HORIZONS("dhDepthTex0", "dhProjectionInverse", false),
        #endif
        // Voxy draws its translucents beside its opaque LoDs, before either seam.
        VOXY("vxDepthTexOpaque", "vxProjInv", false);

        private final String depth;
        private final String projectionInverse;
        private final boolean raw;

        Source(String depth, String projectionInverse, boolean raw) {
            this.depth = depth;
            this.projectionInverse = projectionInverse;
            this.raw = raw;
        }
        }

    private record Key(String fragment, Source source, boolean reversed) {
    }
}
