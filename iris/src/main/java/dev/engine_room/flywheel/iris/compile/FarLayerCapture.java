package dev.engine_room.flywheel.iris.compile;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.far.FarLayerView;
import dev.engine_room.flywheel.backend.engine.far.FarStore;
import dev.engine_room.flywheel.backend.engine.far.FarGlState;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

import java.util.List;

import org.joml.Matrix4f;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reconstructs a bounded source/pixel window without shading or flattening it. The caller submits every window,
 * bounds its maximum hit count before drawing, and restores borrowed host state around the complete sequence.
 */
public final class FarLayerCapture implements AutoCloseable {
    private final AsyncGlProgram program;
    private final int framebuffer = GL45C.glCreateFramebuffers();
    private final int hits = GL45C.glCreateBuffers();
    private int capacity;

    public FarLayerCapture() {
        var vertex = new Compilation();
        vertex.version(GlslVersion.V430);
        vertex.define("_FLW_FAR_NO_ROWS");
        ShaderAssembly.appendComponents(vertex,
                List.of(FlwPrograms.SOURCES.get(ResourceUtil.rl("internal/far/layer_box.vert"))));
        var fragment = new Compilation();
        fragment.version(GlslVersion.V430);
        fragment.define("_FLW_FAR_NO_ROWS");
        ShaderAssembly.appendComponents(fragment,
                List.of(FlwPrograms.SOURCES.get(ResourceUtil.rl("internal/far/layer_sample.glsl"))));
        fragment.appendComponent(new RawSource("far Iris record writer", WRITER));
        program = AsyncGlProgram.graphics("iris/far_layer_capture", vertex, fragment, _ -> {
        });
        GL45C.glNamedFramebufferDrawBuffer(framebuffer, GL11C.GL_NONE);
        GL45C.glNamedFramebufferReadBuffer(framebuffer, GL11C.GL_NONE);
    }

    public boolean ready() {
        return program.get() != null;
    }

    /**
     * After readiness, captures one complete window. maximumHits bounds pixel coverage times source count; it is
     * an allocation bound, never a truncation policy. submit binds the layered store and draws its chosen boxes.
     */
    public int capture(FarLayerView view, int x, int y, int width, int height, int firstSurface, int surfaceCount,
                       int maximumHits, Consumer<GlProgram> submit) {
        begin(view, x, y, width, height, maximumHits);
        var shader = program.get();
        shader.bind();
        shader.setUInt("_flw_farIrisFirstSurface", firstSurface);
        shader.setUInt("_flw_farIrisSurfaceCount", surfaceCount);
        shader.setUInt("_flw_farIrisWidth", view.width());
        shader.setVec2("_flw_farIrisSize", view.width(), view.height());
        shader.setBool("_flw_farIrisReverseZ", view.reverseZ());
        shader.setMat4("_flw_farIrisInverseViewProjection", new Matrix4f(view.viewProjection()).invert());
        submit.accept(shader);
        return finish();
    }

    void begin(FarLayerView view, int x, int y, int width, int height, int maximumHits) {
        if (capacity < maximumHits) {
            GL45C.glNamedBufferData(hits, FarLayerPixels.HEADER_BYTES + (long) maximumHits * FarLayerPixels.HIT_BYTES,
                    GL15C.GL_DYNAMIC_DRAW);
            capacity = maximumHits;
        }
        GL45C.glNamedBufferSubData(hits, 0, new int[]{0, 0, 0, 0});
        GL45C.glNamedFramebufferParameteri(framebuffer, GL43C.GL_FRAMEBUFFER_DEFAULT_WIDTH, view.width());
        GL45C.glNamedFramebufferParameteri(framebuffer, GL43C.GL_FRAMEBUFFER_DEFAULT_HEIGHT, view.height());
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GlStateManager._viewport(0, 0, view.width(), view.height());
        GlStateManager._enableScissorTest();
        GlStateManager._scissorBox(x, y, width, height);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._enableCull();
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 13, hits);
    }

    int finish() {
        GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        return hits;
    }

    /**
     * Half-open, viewport-clipped conservative coverage for one box. Empty rectangles submit no records.
     */
    public record PixelBounds(int x, int y, int width, int height) {
    }

    /**
     * Borrowed until the next window is captured; the consumer shades it synchronously on the render thread.
     */
    public record Window(int hits, int maximumHits, FarLayerView view) {
    }

    /**
     * After producer and shading readiness, uses the store's animated conservative rectangles for every part.
     */
    public void draw(FarStore store, FarLayerView view, Consumer<Window> shade) {
        draw(store, view, part -> new PixelBounds(part.x(), part.y(), part.width(), part.height()), shade);
    }

    /**
     * After producer and shading readiness, visits every published part and every source/pixel window. bounds
     * supplies projected conservative coverage, avoiding full-viewport traversal for a small distant box.
     */
    public void draw(FarStore store, FarLayerView view, Function<FarStore.LayerPart, PixelBounds> bounds,
                     Consumer<Window> shade) {
        try (var _ = FarGlState.capture()) {
            drawWindows(store, view, bounds, shade);
        }
    }

    private void drawWindows(FarStore store, FarLayerView view, Function<FarStore.LayerPart, PixelBounds> bounds,
                             Consumer<Window> shade) {
        int maximumRecords = (int) ((Math.min(16L * 1024 * 1024, GlCompat.MAX_SHADER_STORAGE_BLOCK_SIZE)
                - FarLayerPixels.HEADER_BYTES) / FarLayerPixels.HIT_BYTES);
        store.forEachLayeredPart(view, part -> {
            var rectangle = bounds.apply(part);
            if (rectangle.width() == 0 || rectangle.height() == 0 || part.surfaceCount() == 0) return;
            long area = (long) rectangle.width() * rectangle.height();
            int sourceWindow = Math.min(part.surfaceCount(),
                    Math.max(1, maximumRecords / (int) Math.min(area, maximumRecords)));
            int pixelWindow = maximumRecords / sourceWindow;
            int windowWidth = Math.min(rectangle.width(), pixelWindow);
            int windowHeight = Math.min(rectangle.height(), pixelWindow / windowWidth);
            for (int first = 1; first <= part.surfaceCount(); first += sourceWindow) {
                int sourceCount = Math.min(sourceWindow, part.surfaceCount() - first + 1);
                for (int y = rectangle.y(); y < rectangle.y() + rectangle.height(); y += windowHeight) {
                    int height = Math.min(windowHeight, rectangle.y() + rectangle.height() - y);
                    for (int x = rectangle.x(); x < rectangle.x() + rectangle.width(); x += windowWidth) {
                        int width = Math.min(windowWidth, rectangle.x() + rectangle.width() - x);
                        int maximumHits = width * height * sourceCount;
                        int buffer = capture(view, x, y, width, height, first, sourceCount, maximumHits,
                                shader -> store.drawLayeredBox(shader, view, part.partSlot()));
                        shade.accept(new Window(buffer, maximumHits, view));
                    }
                }
            }
        });
    }

    @Override
    public void close() {
        program.close();
        GL30C.glDeleteFramebuffers(framebuffer);
        GL15C.glDeleteBuffers(hits);
    }

    static final String RECORDS = FarLayerPixels.RECORD + """
            layout(std430, binding=13) buffer _FlwFarHits {
                uvec4 _flw_farHitHeader;
                _FlwFarHit _flw_farHits[];
            };
            """;

    private static final String WRITER = RECORDS + """
            flat in uint _flw_farLayerPartSlot;
            in vec3 _flw_farLayerWorld;
            uniform uint _flw_farIrisFirstSurface;
            uniform uint _flw_farIrisSurfaceCount;
            uniform uint _flw_farIrisWidth;
            uniform vec2 _flw_farIrisSize;
            uniform mat4 _flw_farIrisInverseViewProjection;
            uniform bool _flw_farIrisReverseZ;
            void main() {
                vec2 uv = gl_FragCoord.xy / _flw_farIrisSize;
                bool perspective = any(notEqual(vec3(_flw_farLayerViewProjection[0][3],
                        _flw_farLayerViewProjection[1][3], _flw_farLayerViewProjection[2][3]), vec3(0.0)));
                vec3 origin = _flw_farLayerCamera;
                vec3 direction = _flw_farLayerWorld - origin;
                if (!perspective) {
                    float lowDepth = _flw_farLayerZeroToOne ? 0.0 : -1.0;
                    vec4 nearPoint = _flw_farIrisInverseViewProjection * vec4(uv * 2.0 - 1.0,
                            _flw_farIrisReverseZ ? 1.0 : lowDepth, 1.0);
                    vec4 farPoint = _flw_farIrisInverseViewProjection * vec4(uv * 2.0 - 1.0,
                            _flw_farIrisReverseZ ? lowDepth : 1.0, 1.0);
                    origin = nearPoint.xyz / nearPoint.w;
                    direction = farPoint.xyz / farPoint.w - origin;
                }
                FarLayerRay ray = _flw_farBeginRay(_flw_farLayerPartSlot, origin, direction,
                        _flw_farIrisFirstSurface, _flw_farIrisSurfaceCount);
                FarLayerHit hit;
                while (_flw_farNextHit(ray, hit)) {
                    uint index = atomicAdd(_flw_farHitHeader.x, 1u);
                    vec3 world = hit.positionDepth.xyz - _flw_farLayerCamera;
                    uint pixel = uint(gl_FragCoord.x) + uint(gl_FragCoord.y) * _flw_farIrisWidth;
                    _flw_farHits[index] = _FlwFarHit(hit.albedo, hit.normalDiffuse, hit.lightCoverage, hit.material,
                            vec4(world, hit.positionDepth.w), uvec4(hit.identity.xyz, pixel), hit.tangent);
                }
            }
            """;
}
