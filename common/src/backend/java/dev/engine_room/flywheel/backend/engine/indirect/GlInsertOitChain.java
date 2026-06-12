package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.util.List;
import java.util.OptionalDouble;

import static org.lwjgl.opengl.GL45.*;

public final class GlInsertOitChain {
    private static final int ABUF_DEPTH = 8;
    private static final List<String> CHUNK_SECTION_UNIFORM = List.of("ChunkSection");
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

    private static void drawWeatherColumns(RenderPass pass, AbstractTexture texture, int startColumn, int columnCount) {
        if (columnCount == 0) {
            return;
        }
        pass.bindTexture("Sampler0", texture.getTextureView(), texture.getSampler());
        pass.drawIndexed(columnCount * 6, 1, startColumn * 6, 0, 0);
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
                          @Nullable BerTranslucentCapture ber, @Nullable SodiumTerrainOitReplay terrain,
                          @Nullable FabulousCaptures fabulous, OitInsertMode mode, InsertProducerGeometry producer) {
        boolean hasChunks = chunks != null;
        boolean hasBer = ber != null && !ber.isEmpty();
        boolean hasFabulous = fabulous != null && fabulous.hasAny();
        boolean hasTerrain = terrain != null;
        if (!hasInstanceOit && !hasChunks && !hasBer && !hasFabulous && !hasTerrain) {
            return false;
        }
        if (hasInstanceOit && (vertexBuffer == null || indexBuffer == null)) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget translucentTarget = mc.levelRenderer.translucentTarget();
        RenderTarget target = translucentTarget != null ? translucentTarget : mc.gameRenderer.mainRenderTarget();
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
            terrain.prepareCull(depthView, width, height);
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
        int layerMask = fabulous != null ? fabulous.layerMask(!OitConfig.exactFabulous()) : 0;
        writeUniforms(width, height, maxNodes, layers, layerMask);

        framebuffer.prepareLayersOnly(); // size the fabulous layer targets; no wavelet chain targets are allocated
        OitFrame frame = OitFrame.capture(renderModelView, hasInstanceOit ? vertexBuffer : null,
                hasInstanceOit ? indexBuffer : null, null);
        CommandEncoder encoder = frame.encoder();

        if (fabulous != null) {
            CloudsOitReplay.prepass(encoder, fabulous, framebuffer);
            if (!OitConfig.exactFabulous()) {
                WeatherOitReplay.prepass(encoder, fabulous, framebuffer);
            }
        }

        RenderPass.RenderArea area = new RenderPass.RenderArea(0, 0, width, height);

        RenderPassDescriptor producerDesc = RenderPassDescriptor.create(() -> "flywheel:oit/mlab/producers")
                                                                .withColorAttachment(colorView)
                                                                .withDepthAttachment(depthView, OptionalDouble.empty())
                                                                .withRenderArea(area);
        GlCompat.pushDebugGroup("flywheel:gl/oit/mlab/producers");
        try (RenderPass pass = encoder.createRenderPass(producerDesc)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", frame.dynamicTransforms());
            bindStorage(mode);
            if (frame.vertexBuffer() != null) {
                pass.setVertexBuffer(0, frame.vertexBuffer().slice());
                pass.setIndexBuffer(frame.indexBuffer(), IndexType.INT);
            }
            pass.bindTexture("Sampler1", frame.overlayView(), frame.loSampler());
            pass.bindTexture("Sampler2", frame.lightmapView(), frame.loSampler());
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
            if (fabulous != null && OitConfig.exactFabulous() && fabulous.hasWeather()) {
                replayWeather(pass, fabulous, mode, frame);
            }
        }
        GlCompat.popDebugGroup();

        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        RenderPassDescriptor resolveDesc = RenderPassDescriptor.create(() -> "flywheel:oit/mlab/resolve")
                                                               .withColorAttachment(colorView)
                                                               .withDepthAttachment(depthView, OptionalDouble.empty())
                                                               .withRenderArea(area);
        GlCompat.pushDebugGroup("flywheel:gl/oit/mlab/resolve");
        try (RenderPass pass = encoder.createRenderPass(resolveDesc)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", frame.dynamicTransforms());
            pass.setPipeline(OitPipelines.mlabResolve(mode));
            bindStorage(mode);
            GpuTextureView ph = frame.lightmapView();
            GpuSampler s = frame.oitSampler();
            boolean clouds = fabulous != null && fabulous.hasClouds();
            boolean item = fabulous != null && fabulous.hasItemLayer();
            boolean particle = fabulous != null && fabulous.hasParticleLayer();
            boolean weather = fabulous != null && fabulous.hasWeather() && !OitConfig.exactFabulous();
            pass.bindTexture("_flw_layerColor", clouds ? framebuffer.cloudsColorView() : ph, s);
            pass.bindTexture("_flw_layerDepth", clouds ? framebuffer.cloudsDepthView() : ph, s);
            pass.bindTexture("_flw_layerColor1", item ? fabulous.itemLayerColor : ph, s);
            pass.bindTexture("_flw_layerDepth1", item ? fabulous.itemLayerDepth : ph, s);
            pass.bindTexture("_flw_layerColor2", particle ? fabulous.particleLayerColor : ph, s);
            pass.bindTexture("_flw_layerDepth2", particle ? fabulous.particleLayerDepth : ph, s);
            pass.bindTexture("_flw_layerColor3", weather ? framebuffer.weatherColorView() : ph, s);
            pass.bindTexture("_flw_layerDepth3", weather ? framebuffer.weatherDepthView() : ph, s);
            pass.draw(3, 1, 0, 0);
        }
        GlCompat.popDebugGroup();
        return true;
    }

    private void replayChunks(RenderPass pass, ChunkSectionsToRender sections, OitInsertMode mode, OitFrame frame) {
        var drawGroup = sections.drawGroupsPerLayer().get(ChunkSectionLayer.TRANSLUCENT);
        if (drawGroup == null || drawGroup.isEmpty()) {
            return;
        }
        int maxIndices = sections.maxIndicesRequired();
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = maxIndices == 0 ? null : autoIndices.getBuffer(maxIndices);
        IndexType indexType = maxIndices == 0 ? null : autoIndices.type();
        GpuSampler atlasSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true);

        pass.setPipeline(OitPipelines.chunkMlab(mode));
        pass.bindTexture("Sampler0", sections.textureView(), atlasSampler);
        pass.bindTexture("Sampler2", frame.lightmapView(), frame.loSampler());
        bindStorage(mode); // re-bind after the pipeline prime, defensive against RHI state churn

        GpuBufferSlice[] chunkInfos = sections.chunkSectionInfos();
        for (var draws : drawGroup.values()) {
            if (!draws.isEmpty()) {
                pass.drawMultipleIndexed(draws.reversed(), indexBuffer, indexType, CHUNK_SECTION_UNIFORM, chunkInfos);
            }
        }
    }

    private void replayBer(RenderPass pass, BerTranslucentCapture ber, OitInsertMode mode, OitFrame frame) {
        for (BerFamily family : BerFamily.VALUES) {
            var draws = ber.draws(family);
            if (draws.isEmpty()) {
                continue;
            }
            pass.setPipeline(OitPipelines.berMlab(family, mode));
            if (family.overlay) {
                pass.bindTexture("Sampler1", frame.overlayView(), frame.loSampler());
            }
            if (family.lightmap) {
                pass.bindTexture("Sampler2", frame.lightmapView(), frame.loSampler());
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
                    pass.bindTexture(name, texture.textureView(), texture.sampler());
                }
                pass.setVertexBuffer(0, info.vertexBuffer().slice());
                pass.setIndexBuffer(info.indexBuffer(), info.indexType());
                pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
            }
        }
    }

    private void replayWeather(RenderPass pass, FabulousCaptures fabulous, OitInsertMode mode, OitFrame frame) {
        pass.setPipeline(OitPipelines.weatherMlab(mode));
        pass.setUniform("DynamicTransforms", fabulous.weatherTransform);
        pass.bindTexture("Sampler2", frame.lightmapView(), frame.loSampler());
        bindStorage(mode);
        pass.setVertexBuffer(0, fabulous.weatherVertices.slice());
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        pass.setIndexBuffer(indices.getBuffer(6 * (fabulous.rainColumns + fabulous.snowColumns)), indices.type());
        var tm = frame.textureManager();
        drawWeatherColumns(pass, tm.getTexture(WeatherOitReplay.RAIN_LOCATION), 0, fabulous.rainColumns);
        drawWeatherColumns(pass, tm.getTexture(WeatherOitReplay.SNOW_LOCATION), fabulous.rainColumns,
                fabulous.snowColumns);
    }

    private void bindStorage(OitInsertMode mode) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 24, countOrHead.handle());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 25, data.handle());
        if (mode == OitInsertMode.ABUFFER) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 27, counter.handle());
        }
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 26, ubo);
    }

    private void ensureStorage(OitInsertMode mode, long pixels, int layers, int maxNodes) {
        if (ubo == 0) {
            ubo = glCreateBuffers();
            glNamedBufferStorage(ubo, 32L,
                    org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT); // std140 _FlwMlabUniforms (20B used)
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
        void submit(RenderPass pass, OitInsertMode mode, OitFrame f);
    }
}
