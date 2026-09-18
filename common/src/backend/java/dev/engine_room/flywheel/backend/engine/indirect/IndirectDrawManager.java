package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.BackendDebugFlags;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.SodiumClassLoadCheck;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.RenderPassShaders;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.embed.Environment;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.GlBindlessTable;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferType;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL45C;

import java.util.*;
import java.util.function.Function;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL30.glBindBufferRange;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL40.glDrawElementsIndirect;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43.*;

public class IndirectDrawManager extends DrawManager<IndirectInstancer<?>> {
    private static final Comparator<IndirectDraw> UBER_DRAW_COMPARATOR = Comparator
            .comparingInt(IndirectDraw::bias)
            .thenComparingInt(IndirectDraw::indexOfMeshInModel)
            .thenComparing(IndirectDraw::material, MaterialRenderState::uberPipelineCompare)
            .thenComparing(IndirectDraw::embeddedVariant)
            .thenComparing(IndirectDraw::material, MaterialRenderState.COMPARATOR)
            .thenComparingInt((IndirectDraw d) -> InstanceTypeIds.id(d.instanceType()));
    private static final int UBO_INSTANCE_DRAW = 11;
    private static final int UBO_EMBED_DRAW = 12;
    final MeshPool meshPool;
    final IndirectBuffers buffers = new IndirectBuffers();
    final List<MeshDrawRun> meshMultiDraws = new ArrayList<>();
    final List<MeshDrawRun> meshOitMultiDraws = new ArrayList<>();
    final List<MeshDrawRun> meshOitAdditiveMultiDraws = new ArrayList<>();
    final LightBuffers lightBuffers;
    final RenderPassUniforms renderPassUniforms = new RenderPassUniforms();
    final DepthPyramid depthPyramid;
    final WaveletOitChain oitChain = new WaveletOitChain();
    final GlInsertOitChain insertChain = new GlInsertOitChain();
    protected final Matrix4f renderModelView = new Matrix4f();
    private final IndirectPrograms programs;
    private final StagingBuffer stagingBuffer;
    private final Map<InstanceType<?>, IndirectCullingGroup<?>> cullingGroups = new HashMap<>();
    private final List<IndirectCullingGroup<?>> frameGroups = new ArrayList<>();
    private final List<IndirectDraw> allDraws = new ArrayList<>();
    protected final List<UberDraw> uberMultiDraws = new ArrayList<>();
    protected final List<UberDraw> uberOitMultiDraws = new ArrayList<>();
    protected final List<UberDraw> uberOitAdditiveMultiDraws = new ArrayList<>();
    private final GlBuffer crumblingDrawBuffer = new GlBuffer(GlBufferUsage.STREAM_DRAW);
    private final MatrixBuffer matrixBuffer;
    int frameDrawCount;
    private int frameModelCount;
    private boolean needsDrawBarrier;
    private boolean needsDrawSort;
    private boolean pass2Pending;

    public IndirectDrawManager(IndirectPrograms programs) {
        this.programs = programs;
        programs.acquire();

        // WARN: We should avoid eagerly grabbing GlPrograms here as catching compile
        // errors and falling back during construction is a bit more complicated.
        stagingBuffer = new StagingBuffer(this.programs);
        meshPool = new MeshPool();
        lightBuffers = new LightBuffers();
        matrixBuffer = new MatrixBuffer();

        depthPyramid = new DepthPyramid(programs);
    }

    private static void invalidateEncoderProgramCache() {
        GlStateTracker.invalidateEncoderProgramCache();
    }

    private static void bindDrawUniform(int binding, GpuBufferSlice slice) {
        glBindBufferRange(GL_UNIFORM_BUFFER, binding, ((com.mojang.blaze3d.opengl.GlBuffer) slice.buffer()).handle(),
                slice.offset(), slice.length());
    }

    protected boolean incompatibleUber(IndirectDraw a, IndirectDraw b) {
        if (!MaterialRenderState.uberPipelineEquals(a.material(), b.material())
                || a.embeddedVariant() != b.embeddedVariant()) {
            return true;
        }
        if (!bindlessTextures()) {
            return !a.material().texture().equals(b.material().texture())
                    || a.material().blur() != b.material().blur()
                    || a.material().mipmap() != b.material().mipmap();
        }
        return false;
    }

    @Override
    protected <I extends Instance> IndirectInstancer<?> create(InstancerKey<I> key) {
        return new IndirectInstancer<>(key, new AbstractInstancer.Recreate<>(key, this), GlSlab::new);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected <I extends Instance> void initialize(InstancerKey<I> key, IndirectInstancer<?> instancer) {
        var group = (IndirectCullingGroup<I>) cullingGroups.computeIfAbsent(key.type(), IndirectCullingGroup::new);
        group.add((IndirectInstancer<I>) instancer, key, meshPool, buffers.objectStorage, this);
    }

    /**
     * Shaderpack guest tags of a new draw; {@code null} natively.
     */
    protected @Nullable DrawTags drawTags(Environment environment, Model model, Material material, Mesh mesh) {
        return null;
    }

    @Override
    public void render(LightStorage lightStorage, EnvironmentStorage environmentStorage, Matrix4fc modelViewMatrix,
                       Vec3i renderOrigin, boolean constantAmbientLight) {
        if (prepare(lightStorage, environmentStorage, modelViewMatrix, renderOrigin, constantAmbientLight)) {
            submitMain(modelViewMatrix);
        } else if (SodiumClassLoadCheck.PRESENT) {
            TerrainDrawDispatcher.runDeferredPostVisuals();
        }
        invalidateEncoderProgramCache();
    }

    /**
     * The frame's uploads, before any cull or pass; {@code false} when there is nothing to draw.
     */
    protected boolean prepare(LightStorage lightStorage, EnvironmentStorage environmentStorage,
                              Matrix4fc modelViewMatrix, Vec3i renderOrigin, boolean constantAmbientLight) {
        super.render(lightStorage, environmentStorage, modelViewMatrix, renderOrigin, constantAmbientLight);

        renderModelView.set(modelViewMatrix);
        pass2Pending = false;
        renderPassUniforms.beginFrame(renderOrigin, constantAmbientLight);

        cullingGroups.values()
                     .removeIf(group -> {
                         boolean empty = group.flushInstancers();
                         if (group.drawsDirty) {
                             group.drawsDirty = false;
                             needsDrawSort = true;
                         }
                         return empty;
                     });

        // Instancers may have been emptied in the above call, now remove them here.
        instancers.values()
                  .removeIf(instancer -> {
                      if (instancer.instanceCount() > 0) {
                          return false;
                      }
                      instancer.clear();
                      return true;
                  });

        meshPool.setComputeMeshletBounds(wantsMeshletBounds());
        meshPool.flush();

        if (bindlessTextures()) {
            GlBindlessTable.refresh(Minecraft.getInstance().getTextureManager());
        }

        stagingBuffer.reclaim();

        // Genuinely nothing to do, we can just early out.
        // Still process the mesh pool and reclaim fenced staging regions though.
        if (cullingGroups.isEmpty()) {
            frameGroups.clear();
            allDraws.clear();
            uberMultiDraws.clear();
            uberOitMultiDraws.clear();
            uberOitAdditiveMultiDraws.clear();
            meshMultiDraws.clear();
            meshOitMultiDraws.clear();
            meshOitAdditiveMultiDraws.clear();
            return false;
        }

        frameGroups.clear();
        frameGroups.addAll(cullingGroups.values());
        int modelCount = 0;
        int instanceCount = 0;
        for (var group : frameGroups) {
            for (var instancer : group.instancers()) {
                var count = instancer.instanceCount();
                instancer.update(modelCount++, instanceCount);
                instanceCount += count;
            }
        }
        frameModelCount = modelCount;

        if (needsDrawSort) {
            sortDraws();
            needsDrawSort = false;
        }
        frameDrawCount = allDraws.size();

        lightBuffers.flush(stagingBuffer, lightStorage);

        matrixBuffer.flush(stagingBuffer, environmentStorage);

        buffers.updateCounts(instanceCount, modelCount, frameDrawCount);

        uploadInstances();

        buffers.objectStorage.uploadDescriptors(stagingBuffer);

        uploadModels(stagingBuffer);

        uploadDraws(stagingBuffer);

        needsDrawBarrier = true;

        stagingBuffer.flush();

        // We could probably save some driver calls here when there are
        // actually zero instances, but that feels like a very rare case

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
        return true;
    }

    /**
     * Two-phase HiZ cull + opaque draw of the prepared frame; pass 2 draws at the translucent seam.
     */
    protected void submitMain(Matrix4fc modelViewMatrix) {
        matrixBuffer.bind();

        depthPyramid.bindForCull();

        GlCompat.pushDebugGroup("flywheel:gl/instance_cull");
        dispatchCull();
        GlCompat.popDebugGroup();

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        GlCompat.pushDebugGroup("flywheel:gl/instance_apply");
        programs.getApplyProgram()
                .bind();

        dispatchApply();
        GlCompat.popDebugGroup();

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        GlCompat.pushDebugGroup("flywheel:gl/opaque");
        submitSolid(modelViewMatrix);
        GlCompat.popDebugGroup();

        if (SodiumClassLoadCheck.PRESENT) {
            TerrainDrawDispatcher.runDeferredPostVisuals();
        }

        GlCompat.pushDebugGroup("flywheel:gl/hiz");
        depthPyramid.generate();
        GlCompat.popDebugGroup();
        depthPyramid.bindForCull();
        GlCompat.pushDebugGroup("flywheel:gl/instance_pass2");
        dispatchCullPass2();
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        programs.getApplyProgram()
                .bind();
        dispatchApply2();
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
        GlCompat.popDebugGroup();
        pass2Pending = true;
    }

    /**
     * Culls the prepared frame into the pass-2 buffers with {@code cullProgram} (same buffer interface as the
     * pass-2 cull, no vis words) and applies; {@link #submitUberPass} with {@code pass2} draws the result. Only
     * before {@link #submitMain}: the main cull re-seeds the pass-2 buffers.
     */
    protected void cullIntoPass2(GlProgram cullProgram) {
        GL45C.glCopyNamedBufferSubData(buffers.model.handle(), buffers.model2.handle(), 0, 0,
                IndirectBuffers.MODEL_STRIDE * frameModelCount);
        GL45C.glCopyNamedBufferSubData(buffers.draw.handle(), buffers.draw2.handle(), 0, 0,
                IndirectBuffers.DRAW_COMMAND_STRIDE * frameDrawCount);
        matrixBuffer.bind();
        Uniforms.bindAll();
        cullProgram.bind();
        buffers.bindForCullPass2();
        glDispatchCompute(buffers.objectStorage.pageSlotCount(), 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        programs.getApplyProgram()
                .bind();
        dispatchApply2();
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
        invalidateEncoderProgramCache();
    }

    private void dispatchCull() {
        GL45C.glCopyNamedBufferSubData(buffers.model.handle(), buffers.model2.handle(), 0, 0,
                IndirectBuffers.MODEL_STRIDE * frameModelCount);
        GL45C.glCopyNamedBufferSubData(buffers.draw.handle(), buffers.draw2.handle(), 0, 0,
                IndirectBuffers.DRAW_COMMAND_STRIDE * frameDrawCount);

        Uniforms.bindAll();
        programs.getCullingProgram()
                .bind();

        buffers.bindForCull();
        glDispatchCompute(buffers.objectStorage.pageSlotCount(), 1, 1);
    }

    private void dispatchApply() {
        buffers.bindForApply();
        glDispatchCompute(GlCompat.getComputeGroupCount(frameDrawCount), 1, 1);
    }

    private void dispatchCullPass2() {
        Uniforms.bindAll();
        programs.getCullingPass2Program()
                .bind();

        buffers.bindForCullPass2();
        glDispatchCompute(buffers.objectStorage.pageSlotCount(), 1, 1);
    }

    private void dispatchApply2() {
        buffers.bindForApply2();
        glDispatchCompute(GlCompat.getComputeGroupCount(frameDrawCount), 1, 1);
    }

    private void uploadInstances() {
        int objectVbo = buffers.objectStorage.objectBuffer.handle();
        SlabPageCopier copier = (slab, srcByteOffset, dstByteOffset, byteSize) ->
                GL45C.glCopyNamedBufferSubData(((GlSlab) slab).handle(), objectVbo, srcByteOffset, dstByteOffset,
                        byteSize);
        for (var group : frameGroups) {
            for (var instancer : group.instancers()) {
                instancer.uploadInstances(copier);
            }
        }
    }

    private void uploadModels(StagingBuffer stagingBuffer) {
        var totalSize = frameModelCount * IndirectBuffers.MODEL_STRIDE;
        stagingBuffer.enqueueCopy(totalSize, buffers.model.handle(), 0, this::writeModels);
    }

    private void uploadDraws(StagingBuffer stagingBuffer) {
        var totalSize = frameDrawCount * IndirectBuffers.DRAW_COMMAND_STRIDE;
        stagingBuffer.enqueueCopy(totalSize, buffers.draw.handle(), 0, this::writeCommands);
    }

    private void writeModels(long writePtr) {
        for (var group : frameGroups) {
            writePtr = group.writeModels(writePtr);
        }
    }

    private void writeCommands(long writePtr) {
        for (var draw : allDraws) {
            draw.write(writePtr);
            writePtr += IndirectBuffers.DRAW_COMMAND_STRIDE;
        }
    }

    private void sortDraws() {
        allDraws.clear();
        for (var group : frameGroups) {
            allDraws.addAll(group.indirectDraws);
        }
        allDraws.sort(UBER_DRAW_COMPARATOR);

        uberMultiDraws.clear();
        uberOitMultiDraws.clear();
        uberOitAdditiveMultiDraws.clear();
        meshMultiDraws.clear();
        meshOitMultiDraws.clear();
        meshOitAdditiveMultiDraws.clear();
        int uberStart = 0;
        int meshStart = 0;
        for (int i = 0; i < allDraws.size(); i++) {
            IndirectDraw draw = allDraws.get(i);
            IndirectDraw next = i == allDraws.size() - 1 ? null : allDraws.get(i + 1);
            boolean uberSplit = next == null || incompatibleUber(draw, next);
            boolean additive = OitTransparency.additive(draw.material());
            boolean oit = drawnInTranslucentPass(draw.material());
            if (uberSplit || draw.instanceType() != next.instanceType()) {
                (additive ? meshOitAdditiveMultiDraws : oit ? meshOitMultiDraws : meshMultiDraws).add(
                        new MeshDrawRun(draw.material(), draw.embeddedVariant(), draw.instanceType(), meshStart,
                                i + 1));
                meshStart = i + 1;
            }
            if (uberSplit) {
                (additive ? uberOitAdditiveMultiDraws : oit ? uberOitMultiDraws : uberMultiDraws).add(
                        new UberDraw(draw.material(), draw.embeddedVariant(), draw.drawTag(), uberStart, i + 1));
                uberStart = i + 1;
            }
        }
    }

    void drawBarrier() {
        if (needsDrawBarrier) {
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
            needsDrawBarrier = false;
        }
    }

    boolean wantsMeshletBounds() {
        return false;
    }

    void submitSolid(Matrix4fc modelViewMatrix) {
        submitSolidPass(modelViewMatrix, false);
    }

    void submitOitProducerGeometry(RenderPass pass, OitMode mode, OitFrame f, boolean additive) {
        if (mode != OitMode.DEPTH_RANGE) {
            lightBuffers.bind();
            pass.setUniform("_FlwRenderOrigin", renderPassUniforms.renderOriginSlice());
        }
        matrixBuffer.bind();

        buffers.bindForDraw();
        if (GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
            GlBindlessTable.bind();
        }
        drawBarrier();
        submitUberBatches(pass, additive ? uberOitAdditiveMultiDraws : uberOitMultiDraws,
                batch -> OitPipelines.uberProducer(batch.material(), mode, batch.embedded()),
                mode != OitMode.DEPTH_RANGE, f.textureManager());
    }

    void submitOitInsertProducerGeometry(RenderPass pass, OitInsertMode mode, OitFrame f) {
        lightBuffers.bind();
        pass.setUniform("_FlwRenderOrigin", renderPassUniforms.renderOriginSlice());
        matrixBuffer.bind();
        buffers.bindForDraw();
        if (GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
            GlBindlessTable.bind();
        }
        drawBarrier();
        submitUberBatches(pass, uberOitMultiDraws,
                batch -> OitPipelines.uberMlab(batch.material(), mode, batch.embedded()), true, f.textureManager());
        submitUberBatches(pass, uberOitAdditiveMultiDraws,
                batch -> OitPipelines.uberMlab(batch.material(), mode, batch.embedded()), true, f.textureManager());
    }

    protected void submitPass2IfPending() {
        if (!pass2Pending) {
            return;
        }
        pass2Pending = false;
        GlCompat.pushDebugGroup("flywheel:gl/opaque_pass2");
        submitSolidPass(renderModelView, true);
        GlCompat.popDebugGroup();
    }

    protected boolean drawnInTranslucentPass(Material material) {
        return OitTransparency.orderIndependent(material);
    }

    protected boolean bindlessTextures() {
        return GlCompat.SUPPORTS_BINDLESS_TEXTURES;
    }

    protected RenderPipeline uberPipelineFor(Material material, boolean embedded) {
        return IndirectPipeline.uberPipelineFor(material, embedded);
    }

    protected RenderPipeline crumblingPipelineFor(Material crumblingMaterial, InstanceType<?> type) {
        return CrumblingPipelines.pipeline(crumblingMaterial, type, true);
    }

    private void submitSolidPass(Matrix4fc modelViewMatrix, boolean pass2) {
        submitUberPass(pass2 ? "flywheel:indirect/opaque_pass2" : "flywheel:indirect/opaque", uberMultiDraws,
                modelViewMatrix, pass2, batch -> uberPipelineFor(batch.material(), batch.embedded()));
    }

    /**
     * {@code pass2}: the pass-2 cull output ({@link #cullIntoPass2} or the two-phase HiZ pass).
     */
    protected void submitUberPass(String label, List<UberDraw> batches, Matrix4fc modelViewMatrix, boolean pass2,
                                  Function<UberDraw, RenderPipeline> pipelineFor) {
        GpuBuffer vertexBuffer = meshPool.vertexBuffer();
        GpuBuffer indexBuffer = meshPool.indexBuffer();
        if (vertexBuffer == null || indexBuffer == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice()
                                             .createCommandEncoder();
        var dynamicTransforms = RenderSystem.getDynamicUniforms()
                                            .writeTransform(new Matrix4f(modelViewMatrix));

        TextureManager textureManager = mc.getTextureManager();
        GpuSampler lightOverlaySampler = RenderSystem.getSamplerCache()
                                                     .getClampToEdge(FilterMode.LINEAR);
        GpuTextureView overlayView = mc.gameRenderer.overlayTexture()
                                                    .getTextureView();
        GpuTextureView lightmapView = mc.gameRenderer.lightmap();

        try (RenderPass pass = encoder.createRenderPass(() -> label,
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setVertexBuffer(0, vertexBuffer.slice());
            meshPool.bindExtras(pass);
            pass.setIndexBuffer(indexBuffer, IndexType.INT);
            pass.bindTexture("Sampler1", overlayView, lightOverlaySampler);
            pass.bindTexture("Sampler2", lightmapView, lightOverlaySampler);
            lightBuffers.bind();
            matrixBuffer.bind();
            pass.setUniform("_FlwRenderOrigin", renderPassUniforms.renderOriginSlice());

            if (pass2) {
                buffers.bindForDraw2();
            } else {
                buffers.bindForDraw();
            }
            if (bindlessTextures()) {
                GlBindlessTable.bind();
            }
            drawBarrier();

            submitUberBatches(pass, batches, pipelineFor, true, textureManager);
        }
    }

    private void submitUberBatches(RenderPass pass, List<UberDraw> batches,
                                   Function<UberDraw, RenderPipeline> pipelineFor, boolean bindColor,
                                   TextureManager textureManager) {
        boolean bindless = bindlessTextures();
        RenderPipeline lastPipeline = null;
        Identifier lastTexture = null;
        GpuSampler lastSampler = null;

        for (var batch : batches) {
            RenderPipeline pipeline = pipelineFor.apply(batch);
            boolean needPrime = false;
            if (pipeline != lastPipeline) {
                lastPipeline = pipeline;
                pass.setPipeline(pipeline);
                if (bindColor && RenderPassShaders.readsGeometry(batch.material().light())) GeometryAtlas.bind(pass);
                needPrime = true;
            }

            if (bindColor && !bindless) {
                Identifier texture = batch.material().texture();
                GpuSampler sampler = MaterialSamplers.get(batch.material());
                if (!texture.equals(lastTexture) || sampler != lastSampler) {
                    lastTexture = texture;
                    lastSampler = sampler;
                    AbstractTexture atlas = textureManager.getTexture(texture);
                    pass.bindTexture("Sampler0", atlas.getTextureView(), sampler);
                    needPrime = true;
                }
            }

            if (needPrime) {
                pass.drawIndexed(0, 0, 0, 0, 0);
            }

            bindDrawUniform(UBO_INSTANCE_DRAW, renderPassUniforms.material(0));
            bindDrawUniform(UBO_EMBED_DRAW, renderPassUniforms.embedDraw(batch.start()));

            batch.submitRaw();
        }
    }

    @Override
    public boolean renderOit(LightStorage lightStorage, EnvironmentStorage environmentStorage,
                             @Nullable ChunkSectionsToRender chunks, @Nullable BerTranslucentCapture ber,
                             @Nullable SodiumTerrainOitReplay terrain, @Nullable FabulousCaptures fabulous) {
        submitPass2IfPending();
        if (BackendDebugFlags.SKIP_OIT) {
            return false;
        }
        OitInsertMode insertMode = OitConfig.resolveInsertMode();
        boolean terrainOk = terrain == null || terrain.supportsInsert();
        boolean insertCompatible = insertMode != null && terrainOk;
        if (insertCompatible) {
            return insertChain.render(renderModelView, meshPool.vertexBuffer(), meshPool.indexBuffer(),
                    !uberOitMultiDraws.isEmpty() || !uberOitAdditiveMultiDraws.isEmpty(),
                    !uberOitAdditiveMultiDraws.isEmpty(), null, chunks, ber, terrain, fabulous, insertMode,
                    this::submitOitInsertProducerGeometry);
        }
        return oitChain.render(renderModelView, meshPool.vertexBuffer(), meshPool.indexBuffer(),
                !uberOitMultiDraws.isEmpty() || !uberOitAdditiveMultiDraws.isEmpty(),
                !uberOitAdditiveMultiDraws.isEmpty(), null, chunks, ber, terrain, fabulous,
                this::submitOitProducerGeometry);
    }

    @Override
    public void delete() {
        instancers.values()
                  .forEach(IndirectInstancer::delete);

        super.delete();

        cullingGroups.clear();
        frameGroups.clear();

        buffers.delete();

        stagingBuffer.delete();

        meshPool.delete();

        crumblingDrawBuffer.delete();

        programs.release();

        depthPyramid.delete();

        lightBuffers.delete();

        matrixBuffer.delete();

        renderPassUniforms.delete();

        oitChain.delete();
        insertChain.delete();
    }

    @Override
    public void renderCrumbling(List<Engine.CrumblingBlock> crumblingBlocks) {
        var byType = doCrumblingSort(crumblingBlocks, IndirectInstancer::fromState);

        if (byType.isEmpty()) {
            return;
        }

        GpuBuffer vertexBuffer = meshPool.vertexBuffer();
        GpuBuffer indexBuffer = meshPool.indexBuffer();
        if (vertexBuffer == null || indexBuffer == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice()
                                             .createCommandEncoder();
        var dynamicTransforms = RenderSystem.getDynamicUniforms()
                                            .writeTransform(new Matrix4f(renderModelView));

        TextureManager textureManager = mc.getTextureManager();
        GpuSampler atlasSampler = RenderSystem.getSamplerCache()
                                              .getClampToEdge(FilterMode.LINEAR, true);
        GpuSampler lightOverlaySampler = RenderSystem.getSamplerCache()
                                                     .getClampToEdge(FilterMode.LINEAR);
        GpuSampler crackSampler = RenderSystem.getSamplerCache()
                                              .getRepeat(FilterMode.NEAREST);
        GpuTextureView overlayView = mc.gameRenderer.overlayTexture()
                                                    .getTextureView();
        GpuTextureView lightmapView = mc.gameRenderer.lightmap();

        var crumblingMaterial = SimpleMaterial.builder();
        var block = MemoryBlock.malloc(IndirectBuffers.DRAW_COMMAND_STRIDE);

        GlCompat.pushDebugGroup("flywheel:gl/crumbling");
        try (RenderPass pass = encoder.createRenderPass(() -> "flywheel:indirect/crumbling",
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setVertexBuffer(0, vertexBuffer.slice());
            meshPool.bindExtras(pass);
            pass.setIndexBuffer(indexBuffer, IndexType.INT);
            pass.bindTexture("Sampler1", overlayView, lightOverlaySampler);
            pass.bindTexture("Sampler2", lightmapView, lightOverlaySampler);
            lightBuffers.bind();
            pass.setUniform("_FlwRenderOrigin", renderPassUniforms.renderOriginSlice());

            GlBufferType.DRAW_INDIRECT_BUFFER.bind(crumblingDrawBuffer.handle());

            buffers.bindForCrumbling();
            drawBarrier();

            for (var groupEntry : byType.entrySet()) {
                InstanceType<?> instanceType = groupEntry.getKey()
                                                         .instanceType();

                for (var progressEntry : groupEntry.getValue()
                                                   .int2ObjectEntrySet()) {
                    GpuTextureView crackView = textureManager.getTexture(
                                                                     ModelBakery.BREAKING_LOCATIONS.get(progressEntry.getIntKey()))
                                                             .getTextureView();
                    pass.bindTexture("_flw_crumblingTex", crackView, crackSampler);

                    for (var pair : progressEntry.getValue()) {
                        IndirectInstancer<?> instancer = pair.getFirst();
                        int instanceIndex = pair.getSecond().index;

                        for (IndirectDraw draw : instancer.draws()) {
                            var mesh = draw.mesh();
                            if (mesh.isInvalid()) {
                                continue;
                            }
                            // Transform the material to be suited for crumbling.
                            CommonCrumbling.applyCrumblingProperties(crumblingMaterial, draw.material());
                            pass.setPipeline(crumblingPipelineFor(crumblingMaterial, instanceType));

                            var atlas = textureManager.getTexture(draw.material()
                                                                      .texture());
                            pass.bindTexture("Sampler0", atlas.getTextureView(), atlasSampler);
                            pass.setUniform("_FlwInstanceDraw",
                                    renderPassUniforms.material(MaterialEncoder.packProperties(crumblingMaterial)));

                            draw.writeWithOverrides(block.ptr(), instanceIndex, crumblingMaterial);
                            crumblingDrawBuffer.upload(block);

                            pass.drawIndexed(0, 0, 0, 0, 0);
                            glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0);
                        }
                    }
                }
            }
        } finally {
            block.free();
        }
        GlCompat.popDebugGroup();
    }

    @Override
    public void triggerFallback() {
        IndirectPrograms.kill();
        Minecraft mc = Minecraft.getInstance();
        mc.levelExtractor.allChanged();
    }

    @Override
    public MeshPool meshPool() {
        return meshPool;
    }

    /**
     * {@code drawTag}: the last draw's ({@link IndirectDraw#drawTag}).
     */
    public record UberDraw(Material material, boolean embedded, int drawTag, int start, int end) {
        void submitRaw() {
            long indirect = (long) start * IndirectBuffers.DRAW_COMMAND_STRIDE;
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, indirect, end - start,
                    (int) IndirectBuffers.DRAW_COMMAND_STRIDE);
        }
    }

    record MeshDrawRun(Material material, boolean embedded, InstanceType<?> type, int start, int end) {
    }
}
