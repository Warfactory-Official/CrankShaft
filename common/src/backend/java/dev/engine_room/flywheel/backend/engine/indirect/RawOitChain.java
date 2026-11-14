package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.RenderPassShaders;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.far.FarGlState;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.util.EnumMap;
import java.util.function.Consumer;

/**
 * The existing wavelet/insert algorithms over borrowed GL host targets. A host preflights its producers and
 * {@link #ready} before replacing a draw. Each callback replays all layers for that pass. No frontend pass opens.
 * Own one chain per independently resolved view; close it on resource teardown. Render thread.
 */
public final class RawOitChain implements AutoCloseable {
    private final @Nullable GlInsertOitChain insert;
    private final OitFramebuffer framebuffer;
    private final boolean ownsStorage;

    public RawOitChain() {
        insert = new GlInsertOitChain();
        framebuffer = insert.framebuffer();
        ownsStorage = true;
    }

    private RawOitChain(OitFramebuffer framebuffer, @Nullable GlInsertOitChain insert) {
        this.framebuffer = framebuffer;
        this.insert = insert;
        ownsStorage = false;
    }

    /**
     * Borrow existing main-chain targets and optional insert storage; this adapter never resolves them.
     */
    public static RawOitChain borrowed(OitFramebuffer framebuffer, @Nullable GlInsertOitChain insert) {
        return new RawOitChain(framebuffer, insert);
    }

    private final EnumMap<OitInsertMode, AsyncGlProgram> resolves = new EnumMap<>(OitInsertMode.class);
    private @Nullable AsyncGlProgram composite;
    private @Nullable AsyncGlProgram depthWrite;
    private @Nullable ShaderSources sources;
    private int framebufferId;
    private int vertexArray;
    private @Nullable AsyncGlProgram snapshotBuild;
    private int opaqueDepth;
    private int snapshotWidth;
    private int snapshotHeight;

    /**
     * Requests only the selected resolve and publishes no incomplete group. Null selects wavelet.
     */
    public boolean ready(@Nullable OitInsertMode mode) {
        if (sources != FlwPrograms.SOURCES) {
            closePrograms();
            sources = FlwPrograms.SOURCES;
        }
        if (depthWrite == null) depthWrite = depthProgram();
        boolean snapshotReady = snapshotReady();
        if (mode == null) {
            if (composite == null) {
                var fragment = stage();
                RawOitShaders.append(fragment, "internal/wavelet.glsl", "internal/oit_composite.frag");
                composite = program("composite", fragment);
            }
            return snapshotReady && composite.get() != null && depthWrite.get() != null && NoiseTextures.BLUE_NOISE != null;
        }
        var resolve = resolves.computeIfAbsent(mode, selected -> {
            var fragment = stage();
            RenderPassShaders.mlabResolveDefines(fragment, selected);
            RawOitShaders.append(fragment, "internal/mlab.glsl", "internal/mlab_resolve.frag");
            return program(selected.name(), fragment);
        });
        return snapshotReady && resolve.get() != null && depthWrite.get() != null;
    }

    /**
     * Only the opaque snapshot program is needed when a main graph owns storage and resolve.
     */
    public boolean borrowedReady() {
        if (sources != FlwPrograms.SOURCES) {
            closePrograms();
            sources = FlwPrograms.SOURCES;
        }
        return snapshotReady();
    }

    private boolean snapshotReady() {
        if (snapshotBuild == null) {
            var copy = stage();
            copy.appendComponent(new RawSource("raw OIT opaque snapshot", """
                    layout(local_size_x=8,local_size_y=8) in;
                    layout(binding=16) uniform sampler2D sourceDepth;
                    layout(binding=0,r32f) writeonly uniform image2D snapshotDepth;
                    void main() {
                        ivec2 at=ivec2(gl_GlobalInvocationID.xy);
                        if(any(greaterThanEqual(at,imageSize(snapshotDepth)))) return;
                        imageStore(snapshotDepth,at,vec4(texelFetch(sourceDepth,at,0).r));
                    }
                    """));
            snapshotBuild = AsyncGlProgram.compute("raw_oit/snapshot", copy, _ -> {
            });
        }
        return snapshotBuild.get() != null;
    }

    /**
     * Allocates this view's private targets before entering a host render pass.
     */
    public void prepare(int width, int height, @Nullable OitInsertMode mode) {
        if (mode == null) framebuffer.prepare(width, height);
        else {
            framebuffer.prepareSize(width, height);
            framebuffer.prepareNearestDepth(width, height);
        }
        prepareScratch(width, height);
    }

    private void prepareScratch(int width, int height) {
        if (width != snapshotWidth || height != snapshotHeight) {
            if (opaqueDepth != 0) {
                GL11C.glDeleteTextures(opaqueDepth);
                FlwMemoryTracker._freeGpuMemory((long) snapshotWidth * snapshotHeight * 4);
            }
            opaqueDepth = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(opaqueDepth, 1, GL30C.GL_R32F, width, height);
            snapshotWidth = width;
            snapshotHeight = height;
            FlwMemoryTracker._allocGpuMemory((long) width * height * 4);
        }
        if (framebufferId == 0) framebufferId = GL45C.glCreateFramebuffers();
        if (vertexArray == 0) vertexArray = GL45C.glCreateVertexArrays();
    }

    /**
     * Returns false without taking ownership while asynchronous resolve programs are pending.
     */
    public boolean render(RawOitTarget target, @Nullable OitInsertMode mode, Consumer<RawOitPass> producer) {
        if (!ready(mode)) return false;
        try (var _ = FarGlState.capture()) {
            prepare(target.width(), target.height(), mode);
            captureOpaque(target);
            if (mode == null) {
                for (OitMode pass : new OitMode[]{OitMode.DEPTH_RANGE, OitMode.GENERATE_COEFFICIENTS, OitMode.EVALUATE}) {
                    attachWavelet(target, pass);
                    if (pass == OitMode.DEPTH_RANGE)
                        clear(0, -Float.MAX_VALUE, -Float.MAX_VALUE, 0, 0);
                    else for (int i = 0; i < (pass == OitMode.GENERATE_COEFFICIENTS ? 4 : 1); i++)
                        clear(i, 0, 0, 0, 0);
                    var window = new RawOitPass(target, pass, null, opaqueDepth,
                            () -> bindProducer(target, pass, null));
                    window.bind();
                    producer.accept(window);
                    GL42C.glMemoryBarrier(GL42C.GL_FRAMEBUFFER_BARRIER_BIT | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT);
                }
            } else {
                insert.beginStorage(mode, target.width(), target.height(), 0);
                var window = new RawOitPass(target, null, mode, opaqueDepth, () -> bindProducer(target, null, mode));
                window.bind();
                producer.accept(window);
                GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
            }
            resolve(target, mode);
        }
        return true;
    }

    /**
     * Copies this view's opaque depth once, before any shared producer pass. No target storage is cleared.
     */
    public void beginBorrowed(RawOitTarget target) {
        try (var state = FarGlState.capture()) {
            prepareScratch(target.width(), target.height());
            captureOpaque(target);
        }
    }

    /**
     * Rebinds borrowed producer attachments/read textures/storage; never starts or finishes a separate graph.
     */
    public RawOitPass borrowedPass(RawOitTarget target, @Nullable OitMode wavelet, @Nullable OitInsertMode mode) {
        return new RawOitPass(target, wavelet, mode, opaqueDepth, () -> bindProducer(target, wavelet, mode));
    }

    private void captureOpaque(RawOitTarget target) {
        snapshotBuild.get().bind();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 16);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, target.depth());
        GL33C.glBindSampler(16, ((GlSampler) RenderSystem.getSamplerCache()
                                                         .getClampToEdge(FilterMode.NEAREST)).getId());
        GL42C.glBindImageTexture(0, opaqueDepth, 0, false, 0, GL15C.GL_WRITE_ONLY, GL30C.GL_R32F);
        GL42C.glMemoryBarrier(GL42C.GL_FRAMEBUFFER_BARRIER_BIT);
        GL43C.glDispatchCompute((target.width() + 7) / 8, (target.height() + 7) / 8, 1);
        GL42C.glMemoryBarrier(GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    private void bindProducer(RawOitTarget target, @Nullable OitMode wavelet, @Nullable OitInsertMode mode) {
        if (mode == null) attachWavelet(target, wavelet);
        else attach(target, target.color());
        GlStateManager._viewport(0, 0, target.width(), target.height());
        // Host setup uses raw setters; reinstall the producer contract through tracked entry points.
        GlStateManager.SCISSOR.mode.enabled = true;
        GlStateManager.DEPTH.mode.enabled = false;
        GlStateManager.DEPTH.func = -1;
        GlStateManager.DEPTH.mask = true;
        GlStateManager.CULL.enable.enabled = true;
        GlStateManager._disableScissorTest();
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(target.reverseZ() ? GL11C.GL_GEQUAL : GL11C.GL_LEQUAL);
        GlStateManager._depthMask(false);
        GlStateManager._disableCull();
        stencil(target);
        int outputs = wavelet == OitMode.GENERATE_COEFFICIENTS ? 4 : 1;
        for (int i = 0; i < outputs; i++) {
            GlStateManager.COLOR_MASK[i] = -1;
            #if MC >= 26.3
            GlStateManager.BLEND_ENABLE[i] = mode != null;
            #else
            if (i == 0) GlStateManager.BLEND[0].mode.enabled = mode != null;
            #endif
            GlStateManager._colorMask(i, mode == null ? 15 : 0);
            if (mode == null) GlStateManager._enableBlend(i);
            else GlStateManager._disableBlend(i);
        }
        #if MC >= 26.3
        var blend = GlStateManager.BLEND;
        #else
        var blend = GlStateManager.BLEND[0];
        #endif
        blend.srcRgb = -1;
        blend.modeRgb = -1;
        GlStateManager._blendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE);
        int operation = wavelet == OitMode.DEPTH_RANGE ? GL14C.GL_MAX : GL14C.GL_FUNC_ADD;
        GlStateManager._blendEquationSeparate(operation, operation);
        if (mode != null) insert.bindStorage(mode);
        else bindWaveletReads(wavelet);
    }

    private void bindWaveletReads(@Nullable OitMode mode) {
        if (mode == OitMode.DEPTH_RANGE) return;
        texture(RawOitShaders.DEPTH_RANGE_UNIT, framebuffer.depthBoundsView());
        texture(RawOitShaders.NOISE_UNIT, NoiseTextures.BLUE_NOISE.getTextureView());
        GL33C.glBindSampler(RawOitShaders.NOISE_UNIT, ((GlSampler) RenderSystem.getSamplerCache()
                                                                               .getRepeat(FilterMode.LINEAR)).getId());
        if (mode == OitMode.EVALUATE || mode == null)
            for (int i = 0; i < 4; i++) texture(RawOitShaders.COEFFICIENT_UNIT + i, framebuffer.coefficientsView(i));
    }

    private void resolve(RawOitTarget target, @Nullable OitInsertMode mode) {
        if (mode == null) {
            attach(target, target.color());
            bindWaveletReads(null);
            texture(RawOitShaders.ACCUMULATE_UNIT, framebuffer.accumulateView());
        } else {
            attach(target, target.color(), id(framebuffer.nearestDepthView()));
            clear(1, -1, 0, 0, 0);
            insert.bindStorage(mode);
        }
        GlStateManager._glBindVertexArray(vertexArray);
        GlStateManager._viewport(0, 0, target.width(), target.height());
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._disableCull();
        GlStateManager._disableScissorTest();
        stencil(target);
        GlStateManager._enableBlend(0);
        GlStateManager._colorMask(0, 15);
        if (mode != null) {
            #if MC >= 26.3
            GlStateManager._disableBlend(1);
            #else
            // 26.2: _disableBlend(index) disables every draw buffer; this MRT must leave color blending enabled.
            GL30C.glDisablei(GL11C.GL_BLEND, 1);
            #endif
            GlStateManager._colorMask(1, 15);
        }
        GlStateManager._blendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
        GlStateManager._blendFuncSeparate(mode == null ? GL11C.GL_SRC_ALPHA : GL11C.GL_ONE,
                GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
        GlProgram program = mode == null ? composite.get() : resolves.get(mode).get();
        program.bind();
        RawOitShaders.samplers(program);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

        GL42C.glMemoryBarrier(GL42C.GL_FRAMEBUFFER_BARRIER_BIT | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT);
        attach(target, target.color());
        GlStateManager._disableBlend(0);
        GlStateManager._colorMask(0, 0);
        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);
        GlStateManager._depthFunc(target.reverseZ() ? GL11C.GL_GEQUAL : GL11C.GL_LEQUAL);
        program = depthWrite.get();
        program.bind();
        RawOitShaders.bind(program, target);
        GL20C.glUniform1i(program.getUniformLocation("_flw_rawInsert"), mode != null ? 1 : 0);
        try (var stack = MemoryStack.stackPush()) {
            GL20C.glUniformMatrix4fv(program.getUniformLocation("_flw_rawProjection"), false,
                    target.projection().get(stack.mallocFloat(16)));
        }
        texture(RawOitShaders.ACCUMULATE_UNIT,
                mode == null ? framebuffer.accumulateView() : framebuffer.nearestDepthView());
        if (mode == null) texture(RawOitShaders.DEPTH_RANGE_UNIT, framebuffer.depthBoundsView());
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
    }

    private static void stencil(RawOitTarget target) {
        if (target.stencil() < 0) GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        else {
            GL11C.glEnable(GL11C.GL_STENCIL_TEST);
            GL11C.glStencilFunc(GL11C.GL_EQUAL, target.stencil(), 0xff);
            GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
        }
    }

    private void attachWavelet(RawOitTarget target, OitMode mode) {
        switch (mode) {
            case DEPTH_RANGE -> attach(target, id(framebuffer.depthBoundsView()));
            case GENERATE_COEFFICIENTS -> attach(target, id(framebuffer.coefficientsView(0)),
                    id(framebuffer.coefficientsView(1)), id(framebuffer.coefficientsView(2)),
                    id(framebuffer.coefficientsView(3)));
            case EVALUATE -> attach(target, id(framebuffer.accumulateView()));
            default -> throw new IllegalArgumentException("Not a producer pass: " + mode);
        }
    }

    private void attach(RawOitTarget target, int... colors) {
        GL45C.glNamedFramebufferTexture(framebufferId, GL30C.GL_DEPTH_ATTACHMENT, 0, 0);
        GL45C.glNamedFramebufferTexture(framebufferId, GL30C.GL_STENCIL_ATTACHMENT, 0, 0);
        GL45C.glNamedFramebufferTexture(framebufferId, target.depthAttachment(), target.depth(), 0);
        for (int i = 0; i < 4; i++)
            GL45C.glNamedFramebufferTexture(framebufferId, GL30C.GL_COLOR_ATTACHMENT0 + i,
                    i < colors.length ? colors[i] : 0, 0);
        int[] buffers = new int[colors.length];
        for (int i = 0; i < buffers.length; i++) buffers[i] = GL30C.GL_COLOR_ATTACHMENT0 + i;
        GL45C.glNamedFramebufferDrawBuffers(framebufferId, buffers);
        // Native host setup binds raw GL framebuffers between producer callbacks.
        GlStateManager.writeFbo = -1;
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebufferId);
    }

    private void clear(int attachment, float r, float g, float b, float a) {
        GlStateManager._disableScissorTest();
        GlStateManager._colorMask(attachment, 15);
        GL45C.glClearNamedFramebufferfv(framebufferId, GL11C.GL_COLOR, attachment, new float[]{r, g, b, a});
    }

    private static int id(GpuTextureView texture) {
        return ((GlTextureView) texture).glId();
    }

    private static void texture(int unit, GpuTextureView texture) {
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id(texture));
        GL33C.glBindSampler(unit, ((GlSampler) RenderSystem.getSamplerCache()
                                                           .getClampToEdge(FilterMode.NEAREST)).getId());
    }

    private static Compilation stage() {
        var compilation = new Compilation();
        compilation.version(GlslVersion.V430);
        return compilation;
    }

    private static AsyncGlProgram program(String label, Compilation fragment) {
        var vertex = stage();
        RawOitShaders.append(vertex, "internal/fullscreen.vert");
        return AsyncGlProgram.graphics("raw_oit/" + label, vertex, fragment, _ -> {
        });
    }

    private static AsyncGlProgram depthProgram() {
        var fragment = stage();
        fragment.appendComponent(new RawSource("raw OIT depth", """
                uniform sampler2D _flw_accumulate;
                uniform sampler2D _flw_depthRange;
                uniform bool _flw_rawInsert;
                uniform bool _flw_rawReverseZ;
                uniform bool _flw_rawZeroToOne;
                uniform mat4 _flw_rawProjection;
                layout(location=0) out vec4 color;
                void main() {
                    ivec2 at = ivec2(gl_FragCoord.xy);
                    float depth;
                    if (_flw_rawInsert) {
                        depth = texelFetch(_flw_accumulate, at, 0).r;
                        if (depth < 0.0) discard;
                        depth = _flw_rawReverseZ ? depth : 1.0 - depth;
                    } else {
                        if (texelFetch(_flw_accumulate, at, 0).a < 1e-5) discard;
                        float nearViewZ = texelFetch(_flw_depthRange, at, 0).r - 1e-5;
                        vec4 clip = _flw_rawProjection * vec4(0.0, 0.0, nearViewZ, 1.0);
                        depth = clip.z / clip.w;
                        if (!_flw_rawZeroToOne) depth = depth * 0.5 + 0.5;
                    }
                    gl_FragDepth = depth;
                    color = vec4(0.0);
                }
                """));
        return program("depth", fragment);
    }

    private void closePrograms() {
        if (composite != null) composite.close();
        if (depthWrite != null) depthWrite.close();
        if (snapshotBuild != null) snapshotBuild.close();
        snapshotBuild = null;
        resolves.values().forEach(AsyncGlProgram::close);
        resolves.clear();
        composite = depthWrite = null;
    }

    @Override
    public void close() {
        closePrograms();
        if (ownsStorage) insert.delete();
        if (framebufferId != 0) {
            GL30C.glDeleteFramebuffers(framebufferId);
            GL30C.glDeleteVertexArrays(vertexArray);
            framebufferId = vertexArray = 0;
        }
        sources = null;
        if (opaqueDepth != 0) {
            GL11C.glDeleteTextures(opaqueDepth);
            FlwMemoryTracker._freeGpuMemory((long) snapshotWidth * snapshotHeight * 4);
            opaqueDepth = 0;
            snapshotWidth = snapshotHeight = 0;
        }
    }
}
