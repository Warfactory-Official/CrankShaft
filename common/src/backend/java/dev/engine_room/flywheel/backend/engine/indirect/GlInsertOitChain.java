package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.engine.*;
#if MC >= 26.3
import dev.engine_room.flywheel.backend.engine.ChunkReplayData;
#endif
import dev.engine_room.flywheel.backend.engine.NativeOitReplay;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.compat.backend.RenderPassOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.lwjgl.opengl.GL45.*;

public final class GlInsertOitChain {
    private static final int ABUF_DEPTH = 8;
    private static final int COUNT_OR_HEAD_BINDING = 24;
    private static final int DATA_BINDING = 25;
    private static final int COUNTER_BINDING = 27;
    private static final List<String> CHUNK_SECTION_UNIFORM = List.of("ChunkSection");
    #if MC >= 26.3
    // Resolve layer composite inputs; no Fabulous layer feeds them.
    private static final List<String> LAYER_SAMPLERS = List.of("_flw_layerColor", "_flw_layerDepth",
            "_flw_layerColor1", "_flw_layerDepth1", "_flw_layerColor2", "_flw_layerDepth2", "_flw_layerColor3",
            "_flw_layerDepth3");
    #endif
    private final OitFramebuffer framebuffer = new OitFramebuffer();
    @Nullable
    private ResizableStorageBuffer countOrHead; // per-pixel uint: sample count (k-buffer/MLAB) or list head (A-buffer)
    @Nullable
    private ResizableStorageBuffer data;        // K*8B packed samples, or the A-buffer uvec4 node pool
    @Nullable
    private ResizableStorageBuffer counter;     // A-buffer global node allocator (null for the packed modes)
    private int ubo;                            // host-writable _FlwMlabUniforms (raw GL name; bound at 26)
    @Nullable
    private OitInsertMode allocatedMode;

    public GlInsertOitChain() {
    }

    /**
     * Whether the host accepts {@code mlab.glsl}'s storage bindings; Intel Windows GL caps them at 16.
     */
    public static boolean isSupported() {
        return GlCompat.MAX_SHADER_STORAGE_BUFFER_BINDINGS > COUNTER_BINDING;
    }

    private static void clearWord(int handle, long sizeBytes, int value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var v = stack.mallocInt(1);
            v.put(0, value);
            glClearNamedBufferSubData(handle, GL30.GL_R32UI, 0L, sizeBytes, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT,
                    v);
        }
    }

    public OitFramebuffer framebuffer() {
        return framebuffer;
    }

    public boolean render(Matrix4fc renderModelView, @Nullable GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer,
                          boolean hasInstanceOit, @Nullable Runnable prePass, @Nullable ChunkSectionsToRender chunks,
                          @Nullable BerTranslucentCapture ber,
                          #if MC < 26.3 @Nullable FabulousCaptures fabulous, #endif
                          @Nullable SodiumTerrainOitReplay terrain, OitInsertMode mode,
                          @Nullable NativeOitReplay nativeReplay, InsertProducerGeometry producer) {
        boolean hasBer = ber != null && !ber.isEmpty();
        boolean idle = !hasInstanceOit && chunks == null && !hasBer && terrain == null && nativeReplay == null;
        #if MC < 26.3
        idle &= fabulous == null || !fabulous.hasAny();
        #endif
        if (idle) {
            return false;
        }
        if (hasInstanceOit && (vertexBuffer == null || indexBuffer == null)) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        #if MC >= 26.3
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        #else
        RenderTarget translucentTarget = mc.levelRenderer.translucentTarget();
        RenderTarget target = translucentTarget != null ? translucentTarget : mc.gameRenderer.mainRenderTarget();
        #endif
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return false;
        }
        int width = target.width;
        int height = target.height;
        if (width <= 0 || height <= 0) {
            return false; // 0-sized target (a transient resize frame): a 0-byte storage alloc/clear is a GL error
        }

        if (prePass != null) {
            prePass.run();
        }
        if (terrain != null) {
            terrain.prepareCull(depthView, width, height, true);
        }

        long pixels = (long) width * height;
        int layers = OitConfig.layersFor(mode); // runtime K (sample budget / A-buffer resolve cap)
        int maxNodes = mode == OitInsertMode.ABUFFER ? (int) Math.min(pixels * ABUF_DEPTH, Integer.MAX_VALUE) : 0;
        ensureStorage(mode, pixels, layers, maxNodes);

        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        clearWord(countOrHead.handle(), pixels * Integer.BYTES, mode == OitInsertMode.ABUFFER ? 0xFFFFFFFF : 0);
        if (counter != null) {
            clearWord(counter.handle(), Integer.BYTES, 0);
        }
        int layerMask = #if MC < 26.3 fabulous != null ? fabulous.layerMask(!OitConfig.exactFabulous()) : #endif 0;
        writeUniforms(width, height, maxNodes, layers, layerMask);

        framebuffer.prepareSize(); // no wavelet chain targets
        OitFrame frame = OitFrame.capture(renderModelView, hasInstanceOit ? vertexBuffer : null,
                hasInstanceOit ? indexBuffer : null, null);
        CommandEncoder encoder = frame.encoder();
        if (nativeReplay != null) nativeReplay.prepareInsert(framebuffer, frame, false);
        #if MC < 26.3
        if (fabulous != null) {
            LayerOitReplay.prepass(encoder, fabulous, framebuffer);
        }
        #endif

        RenderPass.RenderArea area = new RenderPass.RenderArea(0, 0, width, height);

        RenderPassDescriptor producerDesc = RenderPassOps.builder(() -> "flywheel:oit/mlab/producers")
                                                         .withColorAttachment(colorView)
                                                         .withDepthAttachment(depthView, OptionalDouble.empty())
                                                         .withRenderArea(area)
                                                         .build();
        GlCompat.pushDebugGroup("flywheel:gl/oit/mlab/producers");
        try (RenderPass pass = encoder.createRenderPass(producerDesc)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", frame.dynamicTransforms());
            bindStorage(mode);
            if (frame.vertexBuffer() != null) {
                pass.setVertexBuffer(0, frame.vertexBuffer().slice());
                pass.setIndexBuffer(frame.indexBuffer(), IndexType.INT);
            }
            pass.setUniform("Sampler1", frame.overlayView(), frame.loSampler());
            pass.setUniform("Sampler2", frame.lightmapView(), frame.loSampler());
            if (hasInstanceOit) {
                producer.submit(pass, mode, frame);
            }
            if (chunks != null) {
                replayChunks(pass, chunks, mode, frame);
            }
            if (hasBer) {
                replayBer(pass, ber, mode, frame);
            }
            if (terrain != null) {
                bindStorage(mode);
                terrain.replayInsert(pass, mode, frame.lightmapView(), frame.loSampler());
            }
            #if MC < 26.3
            if (fabulous != null && OitConfig.exactFabulous() && fabulous.hasWeather()) {
                WeatherOitReplay.replayInsert(pass, fabulous, mode, frame, () -> bindStorage(mode));
            }
            #endif
        } finally {
            GlCompat.popDebugGroup();
        }

        if (nativeReplay != null) {
            bindStorage(mode);
            nativeReplay.replayInsert(producerDesc, mode, null, frame);
        }
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        framebuffer.prepareNearestDepth();
        resolve(frame, #if MC < 26.3 fabulous, #endif colorView, depthView, area, mode);
        RenderPassDescriptor depthDesc = RenderPassOps.builder(() -> "flywheel:oit/mlab/nearest_depth")
                                                      .withColorAttachment(colorView)
                                                      .withDepthAttachment(depthView, OptionalDouble.empty())
                                                      .withRenderArea(area)
                                                      .build();
        GlCompat.pushDebugGroup("flywheel:gl/oit/mlab/nearest_depth");
        try (RenderPass pass = frame.encoder().createRenderPass(depthDesc)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", frame.dynamicTransforms());
            pass.setRenderPipeline(OitPipelines.mlabNearestDepth());
            pass.setUniform("_flw_mlabNearest", framebuffer.nearestDepthView(), frame.oitSampler());
            pass.draw(3, 1, 0, 0);
        } finally {
            GlCompat.popDebugGroup();
        }
        return true;
    }

    private void resolve(OitFrame frame, #if MC < 26.3 @Nullable FabulousCaptures fabulous, #endif
                         GpuTextureView colorView, GpuTextureView depthView, RenderPass.RenderArea area,
                         OitInsertMode mode) {
        RenderPassDescriptor resolveDesc = RenderPassOps.builder(() -> "flywheel:oit/mlab/resolve")
                                                        .withColorAttachment(colorView)
                                                        .withColorAttachment(framebuffer.nearestDepthView(),
                                                                Optional.<Vector4fc>of(new Vector4f(-1.0f)))
                                                        .withDepthAttachment(depthView, OptionalDouble.empty())
                                                        .withRenderArea(area)
                                                        .build();
        GlCompat.pushDebugGroup("flywheel:gl/oit/mlab/resolve");
        try (RenderPass pass = frame.encoder().createRenderPass(resolveDesc)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", frame.dynamicTransforms());
            pass.setRenderPipeline(OitPipelines.mlabResolve(mode));
            bindStorage(mode);
            GpuTextureView ph = frame.lightmapView();
            GpuSampler s = frame.oitSampler();
            #if MC < 26.3
            boolean clouds = fabulous != null && fabulous.hasClouds();
            boolean item = fabulous != null && fabulous.hasItemLayer();
            boolean particle = fabulous != null && fabulous.hasParticleLayer();
            boolean weather = fabulous != null && fabulous.hasWeather() && !OitConfig.exactFabulous();
            pass.setUniform("_flw_layerColor", clouds ? framebuffer.cloudsColorView() : ph, s);
            pass.setUniform("_flw_layerDepth", clouds ? framebuffer.cloudsDepthView() : ph, s);
            pass.setUniform("_flw_layerColor1", item ? fabulous.itemLayerColor : ph, s);
            pass.setUniform("_flw_layerDepth1", item ? fabulous.itemLayerDepth : ph, s);
            pass.setUniform("_flw_layerColor2", particle ? fabulous.particleLayerColor : ph, s);
            pass.setUniform("_flw_layerDepth2", particle ? fabulous.particleLayerDepth : ph, s);
            pass.setUniform("_flw_layerColor3", weather ? framebuffer.weatherColorView() : ph, s);
            pass.setUniform("_flw_layerDepth3", weather ? framebuffer.weatherDepthView() : ph, s);
            #else
            for (String layer : LAYER_SAMPLERS) {
                pass.setUniform(layer, ph, s);
            }
            #endif
            pass.draw(3, 1, 0, 0);
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    private void replayChunks(RenderPass pass, ChunkSectionsToRender sections, OitInsertMode mode, OitFrame frame) {
        #if MC >= 26.3
        var separate = OitPipelines.chunkMlab(mode, false);
        var indirect = OitPipelines.chunkMlab(mode, true);
        pass.setUniform("Sampler0", ChunkReplayData.textureView(), TerrainAtlasFilter.sampler());
        pass.setUniform("Sampler2", frame.lightmapView(), frame.loSampler());
        bindStorage(mode);
        ChunkReplayData.replay(pass, sections, separate, indirect);
        #else
        var drawGroup = sections.drawGroupsPerLayer().get(ChunkSectionLayer.TRANSLUCENT);
        if (drawGroup == null || drawGroup.isEmpty()) {
            return;
        }
        int maxIndices = sections.maxIndicesRequired();
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = maxIndices == 0 ? null : autoIndices.getBuffer(maxIndices);
        IndexType indexType = maxIndices == 0 ? null : autoIndices.type();
        pass.setRenderPipeline(OitPipelines.chunkMlab(mode));
        pass.setUniform("Sampler0", sections.textureView(), TerrainAtlasFilter.sampler());
        pass.setUniform("Sampler2", frame.lightmapView(), frame.loSampler());
        bindStorage(mode); // re-bind after the pipeline prime, defensive against RHI state churn

        GpuBufferSlice[] chunkInfos = sections.chunkSectionInfos();
        for (var draws : drawGroup.values()) {
            if (!draws.isEmpty()) {
                pass.drawMultipleIndexed(draws.reversed(), indexBuffer, indexType, CHUNK_SECTION_UNIFORM, chunkInfos);
            }
        }
        #endif
    }

    private void replayBer(RenderPass pass, BerTranslucentCapture ber, OitInsertMode mode, OitFrame frame) {
        for (BerFamily family : BerFamily.VALUES) {
            var draws = ber.draws(family);
            if (draws.isEmpty()) {
                continue;
            }
            pass.setRenderPipeline(OitPipelines.berMlab(family, mode));
            if (family.overlay) {
                pass.setUniform("Sampler1", frame.overlayView(), frame.loSampler());
            }
            if (family.lightmap) {
                pass.setUniform("Sampler2", frame.lightmapView(), frame.loSampler());
            }
            bindStorage(mode);
            for (int i = 0; i < draws.size(); i++) {
                var draw = draws.get(i);
                var renderType = draw.renderType();
                var info = draw.info();
                pass.setUniform("DynamicTransforms", renderType.dynamicTransforms());
                var textures = renderType.textures();
                for (int t = 0; t < textures.size(); t++) {
                    var texture = textures.get(t);
                    String name = texture.name();
                    if ((!family.overlay && "Sampler1".equals(name)) || (!family.lightmap && "Sampler2".equals(name))) {
                        continue;
                    }
                    pass.setUniform(name, texture.textureView(), texture.sampler());
                }
                pass.setVertexBuffer(0, info.vertexBuffer().slice());
                pass.setIndexBuffer(info.indexBuffer(), info.indexType());
                pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
            }
        }
    }

    private void bindStorage(OitInsertMode mode) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, COUNT_OR_HEAD_BINDING, countOrHead.handle());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, DATA_BINDING, data.handle());
        if (mode == OitInsertMode.ABUFFER) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, COUNTER_BINDING, counter.handle());
        }
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 26, ubo);
    }

    private void ensureStorage(OitInsertMode mode, long pixels, int layers, int maxNodes) {
        if (ubo == 0) {
            ubo = glCreateBuffers();
            glNamedBufferStorage(ubo, 32L,
                    GL_DYNAMIC_STORAGE_BIT); // std140 _FlwMlabUniforms (20B used)
        }
        if (countOrHead == null) {
            countOrHead = new ResizableStorageBuffer();
        }
        if (countOrHead.capacity() < pixels * Integer.BYTES) {
            countOrHead.ensureCapacity(pixels * Integer.BYTES);
        }
        if (mode != allocatedMode) {
            if (data != null) {
                data.delete();
                data = null;
            }
            if (counter != null) {
                counter.delete();
                counter = null;
            }
            allocatedMode = mode;
        }
        long dataBytes = mode == OitInsertMode.ABUFFER ? (long) maxNodes * 16L : pixels * (long) layers * 8L;
        if (data == null) {
            data = new ResizableStorageBuffer();
        }
        if (data.capacity() < dataBytes) {
            data.ensureCapacity(dataBytes);
        }
        if (mode == OitInsertMode.ABUFFER) {
            if (counter == null) {
                counter = new ResizableStorageBuffer();
            }
            if (counter.capacity() < Integer.BYTES) {
                counter.ensureCapacity(Integer.BYTES);
            }
        }
    }

    private void writeUniforms(int width, int height, int maxNodes, int layers, int layerMask) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var buf = stack.mallocInt(5); // uvec2 size, uint maxNodes, uint K, uint layerMask
            buf.put(0, width).put(1, height).put(2, maxNodes).put(3, layers).put(4, layerMask);
            glNamedBufferSubData(ubo, 0L, buf);
        }
    }

    public void delete() {
        framebuffer.delete();
        if (countOrHead != null) {
            countOrHead.delete();
            countOrHead = null;
        }
        if (data != null) {
            data.delete();
            data = null;
        }
        if (counter != null) {
            counter.delete();
            counter = null;
        }
        if (ubo != 0) {
            glDeleteBuffers(ubo);
            ubo = 0;
        }
        allocatedMode = null;
    }

    public interface InsertProducerGeometry {
        /**
         * Submits both {@code ORDER_INDEPENDENT} and {@code ORDER_INDEPENDENT_ADDITIVE} draws: the producer inserts
         * additive fragments as zero-alpha nodes.
         */
        void submit(RenderPass pass, OitInsertMode mode, OitFrame f);
    }
}
