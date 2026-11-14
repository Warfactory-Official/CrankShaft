package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.BackendDebugFlags;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.SodiumClassLoadCheck;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.PipelineVersions;
import dev.engine_room.flywheel.backend.compile.ProgramGeneration;
import dev.engine_room.flywheel.backend.compile.ProgramAvailability;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.NativeOitReplay;
import dev.engine_room.flywheel.backend.engine.embed.Environment;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.far.SceneOcclusion;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.GlBindlessTable;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.GlGpuTimer;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferType;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
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
    // Port: two-phase HiZ occlusion pays only past this instanced vertex workload; below it (on again above 2x) one
    // frustum-culled pass: no copies, pyramid or pass 2. Re-entering two-phase with a stale pyramid is safe: pass 2
    // re-tests what pass 1 culled.
    private static final long OCCLUSION_VERTICES = 1L << 18;
    final MeshPool meshPool;
    final IndirectBuffers buffers = new IndirectBuffers();
    final List<MeshDrawRun> meshMultiDraws = new ArrayList<>();
    final List<MeshDrawRun> meshOitMultiDraws = new ArrayList<>();
    final List<MeshDrawRun> meshOitAdditiveMultiDraws = new ArrayList<>();
    final LightBuffers lightBuffers;
    final RenderPassUniforms renderPassUniforms = new RenderPassUniforms();
    final DepthPyramid depthPyramid;
    private @Nullable SceneOcclusion sceneOcclusion;
    private SceneOcclusion.@Nullable View sharedOcclusion;
    private final Int2ObjectOpenHashMap<SceneOcclusion.Uniforms> meshDepthUniforms = new Int2ObjectOpenHashMap<>();
    final WaveletOitChain oitChain = new WaveletOitChain();
    final GlInsertOitChain insertChain = new GlInsertOitChain();
    protected final Matrix4f renderModelView = new Matrix4f();
    private final IndirectPrograms programs;
    private final StagingBuffer stagingBuffer;
    private final GlSlabArena slabArena;
    private final Map<InstanceType<?>, IndirectCullingGroup<?>> cullingGroups = new HashMap<>();
    private final List<IndirectCullingGroup<?>> frameGroups = new ArrayList<>();
    private final List<IndirectDraw> allDraws = new ArrayList<>();
    protected final List<UberDraw> uberMultiDraws = new ArrayList<>();
    protected final List<UberDraw> uberOitMultiDraws = new ArrayList<>();
    protected final List<UberDraw> uberOitAdditiveMultiDraws = new ArrayList<>();
    private final dev.engine_room.flywheel.backend.gl.buffer.GlBuffer crumblingDrawBuffer =
            new dev.engine_room.flywheel.backend.gl.buffer.GlBuffer(GlBufferUsage.STREAM_DRAW);
    private final MatrixBuffer matrixBuffer;
    private final ObjectHistory objectHistory;
    private final OrderedInstancers orderedInstancers = new OrderedInstancers();
    private int uploadedOrderedPages = -1;
    int frameDrawCount;
    private int frameModelCount;
    private boolean needsDrawBarrier;
    private boolean needsDrawSort;
    private boolean pass2Pending;
    private boolean occlusion = true;
    /**
     * Instance types the frame's cull program covers; draws of later types get arbitrary visibility.
     */
    private int cullTypes;
    private boolean standaloneHistory;

    public IndirectDrawManager(IndirectPrograms programs) {
        this.programs = programs;
        programs.acquire();

        // WARN: We should avoid eagerly grabbing GlPrograms here as catching compile
        // errors and falling back during construction is a bit more complicated.
        stagingBuffer = new StagingBuffer(this.programs);
        slabArena = new GlSlabArena(stagingBuffer);
        objectHistory = new ObjectHistory(slabArena);
        meshPool = new MeshPool();
        lightBuffers = new LightBuffers();
        matrixBuffer = new MatrixBuffer();

        depthPyramid = new DepthPyramid(programs);
    }

    private static void invalidateEncoderProgramCache() {
        GlStateTracker.invalidateEncoderProgramCache();
    }

    private static void bindDrawUniform(int binding, GpuBufferSlice slice) {
        glBindBufferRange(GL_UNIFORM_BUFFER, binding, ((GlBuffer) slice.buffer()).handle(),
                slice.offset(), slice.length());
    }

    private static boolean textureDiffers(Material a, Material b) {
        return !a.texture().equals(b.texture()) || a.blur() != b.blur() || a.mipmap() != b.mipmap()
                || a.useTextureSampler() != b.useTextureSampler();
    }

    protected boolean incompatibleUber(IndirectDraw a, IndirectDraw b) {
        if (!MaterialRenderState.uberPipelineEquals(a.material(), b.material())
                || a.embeddedVariant() != b.embeddedVariant()) {
            return true;
        }
        return !bindlessTextures() && textureDiffers(a.material(), b.material());
    }

    @Override
    protected <I extends Instance> IndirectInstancer<?> create(InstancerKey<I> key) {
        var population = key.population();
        if (population != null) {
            var instancer = new GpuIndirectInstancer<>(key, new AbstractInstancer.Recreate<>(key, this),
                    slabArena::create, population.simulation(), population.capacity());
            instancer.setPopulation(new GlGpuPopulation(instancer, programs));
            return instancer;
        }
        return new IndirectInstancer<>(key, new AbstractInstancer.Recreate<>(key, this), slabArena::create);
    }

    @Override
    public boolean supportsGpuSimulation() {
        return true;
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
    protected @Nullable DrawTags drawTags(Environment _, Model _, Material _, Mesh _) {
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
        sceneOcclusion = SceneOcclusion.of(Minecraft.getInstance().level);
        super.render(lightStorage, environmentStorage, modelViewMatrix, renderOrigin, constantAmbientLight);

        renderModelView.set(modelViewMatrix);
        pass2Pending = false;
        slabArena.beginFrame();
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
        long vertices = 0;
        orderedInstancers.begin();
        for (var group : frameGroups) {
            for (var instancer : group.instancers()) {
                var count = instancer.instanceCount();
                instancer.update(modelCount++, instanceCount);
                orderedInstancers.add(instancer);
                instanceCount += count;
                vertices += (long) count * instancer.modelVertexCount();
            }
        }
        frameModelCount = modelCount;
        if (!occlusionOptional()) {
            occlusion = true;
        } else if (occlusion ? vertices < OCCLUSION_VERTICES : vertices > 2 * OCCLUSION_VERTICES) {
            occlusion = !occlusion;
        }

        if (needsDrawSort) {
            sortDraws();
            needsDrawSort = false;
        }
        frameDrawCount = allDraws.size();

        lightBuffers.flush(stagingBuffer, lightStorage);

        boolean history = keepsHistory();
        matrixBuffer.flush(stagingBuffer, environmentStorage, history);

        buffers.updateCounts(instanceCount, modelCount, frameDrawCount);

        uploadInstances(history);

        buffers.objectStorage.uploadDescriptors(stagingBuffer);

        uploadModels(stagingBuffer);

        uploadOrdered(stagingBuffer);

        uploadDraws(stagingBuffer);

        needsDrawBarrier = true;

        stagingBuffer.flush();

        // We could probably save some driver calls here when there are
        // actually zero instances, but that feels like a very rare case

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
        for (var group : frameGroups) {
            for (var instancer : group.instancers()) {
                if (instancer instanceof GpuIndirectInstancer<?> gpu) {
                    ((GlGpuPopulation) gpu.population()).prepare(buffers.objectStorage);
                }
            }
        }
        return true;
    }

    /**
     * Two-phase HiZ cull + opaque draw of the prepared frame; pass 2 draws at the translucent seam.
     */
    protected void submitMain(Matrix4fc modelViewMatrix) {
        pass2Pending = false;
        InstanceTypeIds.Snapshot cull = programs.cullSnapshot();
        if (cull == null) {
            cullTypes = 0;
            if (SodiumClassLoadCheck.PRESENT) {
                TerrainDrawDispatcher.runDeferredPostVisuals();
            }
            return;
        }
        cullTypes = cull.types().size();
        matrixBuffer.bind();

        sharedOcclusion = sceneOcclusion.view();
        if (sharedOcclusion != null || !occlusion) standaloneHistory = false;
        bindMainDepth();

        GlCompat.pushDebugGroup("flywheel:gl/instance_cull");
        dispatchCull(cull);
        GlCompat.popDebugGroup();

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        dispatchOrder(false);

        GlCompat.pushDebugGroup("flywheel:gl/instance_apply");
        programs.getApplyProgram()
                .bind();

        dispatchApply();
        GlCompat.popDebugGroup();

        // The apply wrote the indirect commands; drawBarrier's one-shot may already be spent (a guest shadow pass).
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);

        GlCompat.pushDebugGroup("flywheel:gl/opaque");
        submitSolid(modelViewMatrix);
        GlCompat.popDebugGroup();

        if (SodiumClassLoadCheck.PRESENT) {
            TerrainDrawDispatcher.runDeferredPostVisuals();
        }
        if (!occlusion || sharedOcclusion != null) {
            return;
        }

        GlCompat.pushDebugGroup("flywheel:gl/hiz");
        depthPyramid.generate();
        GlCompat.popDebugGroup();
        standaloneHistory = true;
        depthPyramid.bindForCull();
        GlCompat.pushDebugGroup("flywheel:gl/instance_pass2");
        dispatchCullPass2(cull);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        dispatchOrder(true);
        programs.getApplyProgram()
                .bind();
        dispatchApply2();
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
        GlCompat.popDebugGroup();
        pass2Pending = true;
    }

    protected final void bindMainDepth() {
        if (sharedOcclusion != null) sharedOcclusion.bind();
        else depthPyramid.bindForCull();
    }

    private void configureMainDepth(GlProgram program) {
        if (sharedOcclusion != null) sharedOcclusion.configure(program);
        else {
            program.setBool("_flw_sharedDepth", false);
            program.setBool("_flw_occlusionEnabled", occlusion && standaloneHistory);
        }
    }

    protected final void configureMainDepth(int program) {
        meshDepthUniforms.computeIfAbsent(program, SceneOcclusion.Uniforms::new)
                         .configure(sharedOcclusion, sharedOcclusion != null || (occlusion && standaloneHistory));
    }

    /**
     * {@code false}: every frame takes the two-phase HiZ path (a consumer relies on its pyramid).
     */
    boolean occlusionOptional() {
        return true;
    }

    /**
     * Culls the prepared frame into the pass-2 buffers with {@code cullProgram} (same buffer interface as the
     * pass-2 cull, no vis words), covering the first {@code cullTypes} instance types, and applies;
     * {@link #submitUberPass} with {@code pass2} draws the result. Only before {@link #submitMain}: the main cull
     * re-seeds the pass-2 buffers.
     */
    protected void cullIntoPass2(GlProgram cullProgram, int cullTypes) {
        this.cullTypes = cullTypes;
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
        dispatchOrder(true);
        programs.getApplyProgram()
                .bind();
        dispatchApply2();
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
        invalidateEncoderProgramCache();
    }

    private void dispatchCull(InstanceTypeIds.Snapshot cull) {
        if (occlusion && sharedOcclusion == null) {
            GL45C.glCopyNamedBufferSubData(buffers.model.handle(), buffers.model2.handle(), 0, 0,
                    IndirectBuffers.MODEL_STRIDE * frameModelCount);
            GL45C.glCopyNamedBufferSubData(buffers.draw.handle(), buffers.draw2.handle(), 0, 0,
                    IndirectBuffers.DRAW_COMMAND_STRIDE * frameDrawCount);
        }

        Uniforms.bindAll();
        var program = occlusion || sharedOcclusion != null ? programs.getCullingProgram(cull)
                : programs.getCullingFrustumProgram(cull);
        program.bind();
        configureMainDepth(program);

        buffers.bindForCull();
        glDispatchCompute(buffers.objectStorage.pageSlotCount(), 1, 1);
    }

    /**
     * Ordered instancers' visible instances in page order, after the pass's cull and its storage barrier.
     */
    private void dispatchOrder(boolean pass2) {
        int count = orderedInstancers.count();
        if (count == 0) {
            return;
        }
        GlCompat.pushDebugGroup("flywheel:gl/instance_order");
        programs.getOrderProgram()
                .bind();
        buffers.bindForOrder(pass2);
        glDispatchCompute(count, 1, 1);
        if (pass2) {
            buffers.bindForCullPass2();
        } else {
            buffers.bindForCull();
        }
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        GlCompat.popDebugGroup();
    }

    private void dispatchApply() {
        buffers.bindForApply();
        glDispatchCompute(GlCompat.getComputeGroupCount(frameDrawCount), 1, 1);
    }

    private void dispatchCullPass2(InstanceTypeIds.Snapshot cull) {
        Uniforms.bindAll();
        programs.getCullingPass2Program(cull)
                .bind();
        programs.getCullingPass2Program(cull).setBool("_flw_sharedDepth", false);
        programs.getCullingPass2Program(cull).setBool("_flw_occlusionEnabled", true);

        buffers.bindForCullPass2();
        glDispatchCompute(buffers.objectStorage.pageSlotCount(), 1, 1);
    }

    private void dispatchApply2() {
        buffers.bindForApply2();
        glDispatchCompute(GlCompat.getComputeGroupCount(frameDrawCount), 1, 1);
    }

    private void uploadInstances(boolean keepHistory) {
        boolean history = objectHistory.begin(keepHistory, buffers.objectStorage);
        slabArena.beginUpload(buffers.objectStorage.objectBuffer.handle(), history ? objectHistory : null);
        for (var group : frameGroups) {
            for (var instancer : group.instancers()) {
                instancer.uploadInstances(slabArena, history ? objectHistory : null);
                if (history && instancer instanceof GpuIndirectInstancer<?> gpu) {
                    ((GlGpuPopulation) gpu.population()).recordHistory(objectHistory);
                }
            }
        }
        if (history) {
            objectHistory.uploadMoved(stagingBuffer);
        }
    }

    /**
     * Whether to keep the previous frame's instances and embedding poses ({@link #bindHistory}); sampled once per
     * frame in {@link #prepare}.
     */
    protected boolean keepsHistory() {
        return false;
    }

    /**
     * The previous frame's objects at {@link BufferBindings#HISTORY_INSTANCE}, their moved bits at
     * {@link BufferBindings#HISTORY_MOVED} and embedding poses at {@link BufferBindings#HISTORY_MATRICES}, at this
     * frame's object offsets and matrix indices; this frame's own while none are kept.
     */
    protected void bindHistory() {
        objectHistory.bind(buffers.objectStorage.objectBuffer);
        matrixBuffer.bindHistory();
    }

    private void uploadModels(StagingBuffer stagingBuffer) {
        var totalSize = frameModelCount * IndirectBuffers.MODEL_STRIDE;
        stagingBuffer.enqueueCopy(totalSize, buffers.model.handle(), 0, this::writeModels);
    }

    private void uploadOrdered(StagingBuffer stagingBuffer) {
        int count = orderedInstancers.count();
        if (count == 0) {
            return;
        }
        buffers.orderedModels.ensureCapacity(count);
        stagingBuffer.enqueueCopy(count * OrderedInstancers.RECORD_BYTES, buffers.orderedModels.handle(), 0,
                orderedInstancers::writeRecords);
        if (uploadedOrderedPages != orderedInstancers.pageVersion()) {
            uploadedOrderedPages = orderedInstancers.pageVersion();
            int pages = orderedInstancers.pageCount();
            buffers.orderedPages.ensureCapacity(pages);
            stagingBuffer.enqueueCopy(pages * IndirectBuffers.INT_SIZE, buffers.orderedPages.handle(), 0,
                    orderedInstancers::writePages);
        }
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
        ProgramGeneration required = ProgramGeneration.NONE;
        ProgramGeneration meshRequired = ProgramGeneration.NONE;
        for (int i = 0; i < allDraws.size(); i++) {
            IndirectDraw draw = allDraws.get(i);
            IndirectDraw next = i == allDraws.size() - 1 ? null : allDraws.get(i + 1);
            required = required.max(draw.required());
            meshRequired = meshRequired.max(draw.required());
            boolean uberSplit = next == null || incompatibleUber(draw, next);
            boolean additive = drawnInAdditivePass(draw);
            boolean oit = drawnInTranslucentPass(draw);
            if (uberSplit || draw.instanceType() != next.instanceType()) {
                (additive ? meshOitAdditiveMultiDraws : oit ? meshOitMultiDraws : meshMultiDraws).add(
                        new MeshDrawRun(draw.material(), draw.embeddedVariant(), draw.instanceType(), meshStart,
                                i + 1, meshRequired));
                meshStart = i + 1;
                meshRequired = ProgramGeneration.NONE;
            }
            if (uberSplit) {
                (additive ? uberOitAdditiveMultiDraws : oit ? uberOitMultiDraws : uberMultiDraws).add(
                        new UberDraw(draw.material(), draw.embeddedVariant(), draw.drawTag(), uberStart, i + 1,
                                draw.instanceType(), draw.viewDepthSorted(), required));
                uberStart = i + 1;
                required = ProgramGeneration.NONE;
            }
        }
    }

    IndirectDraw draw(int index) {
        return allDraws.get(index);
    }

    int cullTypes() {
        return cullTypes;
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
        List<UberDraw> batches = additive ? uberOitAdditiveMultiDraws : uberOitMultiDraws;
        if (batches.isEmpty()) return;
        pass.setUniform("_FlwRenderOrigin", renderPassUniforms.renderOriginSlice());
        if (mode != OitMode.DEPTH_RANGE) {
            lightBuffers.bind();
        }
        matrixBuffer.bind();

        buffers.bindForDraw();
        if (GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
            GlBindlessTable.bind();
        }
        drawBarrier();
        submitUberBatches(pass, batches,
                batch -> OitPipelines.uberProducer(batch.material(), mode, batch.embedded()), f.textureManager());
    }

    void submitOitInsertProducerGeometry(RenderPass pass, OitInsertMode mode, OitFrame f, boolean additive) {
        List<UberDraw> batches = additive ? uberOitAdditiveMultiDraws : uberOitMultiDraws;
        if (batches.isEmpty()) return;
        lightBuffers.bind();
        pass.setUniform("_FlwRenderOrigin", renderPassUniforms.renderOriginSlice());
        matrixBuffer.bind();
        buffers.bindForDraw();
        if (GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
            GlBindlessTable.bind();
        }
        drawBarrier();
        submitUberBatches(pass, batches,
                batch -> OitPipelines.uberMlab(batch.material(), mode, batch.embedded()), f.textureManager());
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

    protected boolean drawnInTranslucentPass(IndirectDraw draw) {
        return OitTransparency.orderIndependent(draw.material());
    }

    protected boolean drawnInAdditivePass(IndirectDraw draw) {
        return OitTransparency.additive(draw.material());
    }

    /**
     * Whether the draw blends in submission order, so its instancer's culled instances keep page order.
     */
    protected boolean keepsSubmissionOrder(IndirectDraw draw) {
        return SubmissionOrder.matters(draw.material());
    }

    protected boolean bindlessTextures() {
        return GlCompat.SUPPORTS_BINDLESS_TEXTURES;
    }

    /**
     * {@code false}: binds {@code Sampler0}; a texture-merged batch then draws per texture run.
     */
    protected boolean bindlessPipeline(RenderPipeline _) {
        return bindlessTextures();
    }

    /**
     * The newest published uber pipeline, null before the first: the batch skips the frame.
     */
    protected @Nullable RenderPipeline uberPipelineFor(Material material, boolean embedded) {
        return IndirectPipeline.uberPipelineFor(material, embedded);
    }

    /**
     * Requests a new draw's pipelines ahead of its first frame.
     */
    protected void warmUp(Material material, boolean embedded) {
        if (OitTransparency.orderIndependent(material)) {
            OitInsertMode mode = OitConfig.resolveInsertMode();
            if (mode == null) OitPipelines.uberProducer(material, OitMode.EVALUATE, embedded);
            else OitPipelines.uberMlab(material, mode, embedded);
        } else {
            uberPipelineFor(material, embedded);
        }
    }

    protected @Nullable RenderPipeline crumblingPipelineFor(Material crumblingMaterial, InstanceType<?> type) {
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
                                  Function<UberDraw, @Nullable RenderPipeline> pipelineFor) {
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

        boolean timed = GlGpuTimer.isEnabled() && !batches.isEmpty();
        if (timed) GlCompat.pushDebugGroup("pass:" + label);
        try (RenderPass pass = encoder.createRenderPass(() -> label,
                colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setVertexBuffer(0, vertexBuffer.slice());
            meshPool.bindExtras(pass);
            pass.setIndexBuffer(indexBuffer, IndexType.INT);
            pass.setUniform("Sampler1", overlayView, lightOverlaySampler);
            pass.setUniform("Sampler2", lightmapView, lightOverlaySampler);
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

            submitUberBatches(pass, batches, pipelineFor, textureManager);
        } finally {
            if (timed) GlCompat.popDebugGroup();
        }
    }

    // A batch draws with the newest published generation of its pipeline: runs skip the draws it or the frame's cull
    // program does not cover (a later instance type, cutout or fog), and a batch without a pipeline skips the frame.
    private void submitUberBatches(RenderPass pass, List<UberDraw> batches,
                                   Function<UberDraw, @Nullable RenderPipeline> pipelineFor,
                                   TextureManager textureManager) {
        RenderPipeline lastPipeline = null;
        Identifier lastTexture = null;
        GpuSampler lastSampler = null;

        for (var batch : batches) {
            RenderPipeline pipeline = pipelineFor.apply(batch);
            if (pipeline == null) {
                continue;
            }
            ProgramGeneration coverage = coverage(batch, pipeline).culledBy(cullTypes);
            boolean partial = !coverage.covers(batch.required());
            boolean needPrime = false;
            if (pipeline != lastPipeline) {
                lastPipeline = pipeline;
                pass.setRenderPipeline(pipeline);
                needPrime = true;
            }

            boolean bindless = bindlessPipeline(pipeline);
            if (bindless && !partial) {
                if (needPrime) {
                    pass.drawIndexed(0, 0, 0, 0, 0);
                }
                submitRun(pipeline, batch.start(), batch.end());
                continue;
            }

            int runStart = batch.start();
            for (int i = batch.start(); i < batch.end(); i++) {
                IndirectDraw draw = allDraws.get(i);
                if (partial && !coverage.covers(draw.required())) {
                    runStart = i + 1;
                    continue;
                }
                Material material = draw.material();
                if (i + 1 < batch.end()) {
                    IndirectDraw next = allDraws.get(i + 1);
                    if ((bindless || !textureDiffers(material, next.material()))
                            && (!partial || coverage.covers(next.required()))) {
                        continue;
                    }
                }
                if (!bindless) {
                    GpuSampler sampler = MaterialSamplers.get(material);
                    if (!material.texture().equals(lastTexture) || sampler != lastSampler) {
                        lastTexture = material.texture();
                        lastSampler = sampler;
                        AbstractTexture atlas = textureManager.getTexture(lastTexture);
                        pass.setUniform("Sampler0", atlas.getTextureView(), sampler);
                        needPrime = true;
                    }
                }
                if (needPrime) {
                    pass.drawIndexed(0, 0, 0, 0, 0);
                    needPrime = false;
                }
                submitRun(pipeline, runStart, i + 1);
                runStart = i + 1;
            }
        }
    }

    /**
     * The generation {@code pipeline} draws {@code batch}'s draws up to; a consumer drawing the batch in several
     * passes limits it to the generation every pass covers.
     */
    protected ProgramGeneration coverage(UberDraw _, RenderPipeline pipeline) {
        return PipelineVersions.generation(pipeline);
    }

    protected void submitRun(RenderPipeline _, int start, int end) {
        bindDrawUniform(UBO_INSTANCE_DRAW, renderPassUniforms.material(0));
        bindDrawUniform(UBO_EMBED_DRAW, renderPassUniforms.embedDraw(start));
        UberDraw.submitRaw(start, end);
    }

    protected final int drawCommandsBuffer() {
        return buffers.draw.handle();
    }

    @Override
    public boolean renderOit(LightStorage lightStorage, EnvironmentStorage environmentStorage,
                             @Nullable ChunkSectionsToRender chunks, @Nullable BerTranslucentCapture ber,
                             #if MC < 26.3 @Nullable FabulousCaptures fabulous, #endif
                             @Nullable SodiumTerrainOitReplay terrain) {
        return renderOitFrame(lightStorage, environmentStorage, chunks, ber, #if MC < 26.3 fabulous, #endif terrain,
                null);
    }

    @Override
    public boolean renderNativeOit(LightStorage lightStorage, EnvironmentStorage environmentStorage,
                                   NativeOitReplay replay) {
        return renderOitFrame(lightStorage, environmentStorage, null, null, #if MC < 26.3 null, #endif null, replay);
    }

    private boolean renderOitFrame(LightStorage _, EnvironmentStorage _,
                                   @Nullable ChunkSectionsToRender chunks, @Nullable BerTranslucentCapture ber,
                                   #if MC < 26.3 @Nullable FabulousCaptures fabulous, #endif
                                   @Nullable SodiumTerrainOitReplay terrain, @Nullable NativeOitReplay nativeReplay) {
        submitPass2IfPending();
        if (BackendDebugFlags.SKIP_OIT) {
            return false;
        }
        if (OitInstanceCoverage.recording()) {
            recordInstanceCoverage();
        }
        OitInsertMode insertMode = OitConfig.resolveInsertMode();
        boolean terrainOk = (terrain == null || terrain.supportsInsert())
                && (nativeReplay == null || nativeReplay.supportsInsert());
        boolean insertCompatible = insertMode != null && terrainOk;
        if (insertCompatible) {
            try {
                return insertChain.render(renderModelView, meshPool.vertexBuffer(), meshPool.indexBuffer(),
                        !uberOitMultiDraws.isEmpty() || !uberOitAdditiveMultiDraws.isEmpty(),
                        !uberOitAdditiveMultiDraws.isEmpty(), null, chunks, ber,
                        #if MC < 26.3 fabulous, #endif terrain, insertMode, nativeReplay,
                        this::submitOitInsertProducerGeometry);
            } catch (ProgramAvailability.Failure failure) {
                throw failure;
            } catch (BackendUnavailableException failure) {
                throw new ProgramAvailability.Failure(ProgramAvailability.insert(insertMode), failure);
            }
        }
        return oitChain.render(renderModelView, meshPool.vertexBuffer(), meshPool.indexBuffer(),
                !uberOitMultiDraws.isEmpty() || !uberOitAdditiveMultiDraws.isEmpty(),
                !uberOitAdditiveMultiDraws.isEmpty(), null, chunks, ber, #if MC < 26.3 fabulous, #endif terrain,
                nativeReplay, this::submitOitProducerGeometry);
    }

    // Ahead of the chain, whose depth writeback would otherwise stand in for the terrain the coverage is tested against.
    private void recordInstanceCoverage() {
        GpuBuffer vertexBuffer = meshPool.vertexBuffer();
        GpuBuffer indexBuffer = meshPool.indexBuffer();
        if (vertexBuffer == null || indexBuffer == null
                || uberOitMultiDraws.isEmpty() && uberOitAdditiveMultiDraws.isEmpty()) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView depthView = main.getDepthTextureView();
        if (depthView == null) {
            return;
        }
        OitFrame frame = OitFrame.capture(renderModelView, vertexBuffer, indexBuffer, null);
        GlCompat.pushDebugGroup("flywheel:gl/oit/instance_coverage");
        try (RenderPass pass = OitInstanceCoverage.begin(frame.encoder(), depthView, main.width, main.height,
                FrameUniforms.getDepthFar())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", frame.dynamicTransforms());
            pass.setVertexBuffer(0, vertexBuffer.slice());
            pass.setIndexBuffer(indexBuffer, IndexType.INT);
            submitOitProducerGeometry(pass, OitMode.DEPTH_RANGE, frame, false);
            submitOitProducerGeometry(pass, OitMode.DEPTH_RANGE, frame, true);
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    @Override
    public void delete() {
        OitInstanceCoverage.close();
        meshDepthUniforms.clear();
        instancers.values()
                  .forEach(IndirectInstancer::delete);

        super.delete();

        cullingGroups.clear();
        frameGroups.clear();

        buffers.delete();

        stagingBuffer.delete();

        slabArena.delete();

        meshPool.delete();

        crumblingDrawBuffer.delete();

        programs.release();

        depthPyramid.delete();

        lightBuffers.delete();

        matrixBuffer.delete();

        objectHistory.delete();

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
            pass.setUniform("Sampler1", overlayView, lightOverlaySampler);
            pass.setUniform("Sampler2", lightmapView, lightOverlaySampler);
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
                    pass.setUniform("_flw_crumblingTex", crackView, crackSampler);

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
                            RenderPipeline pipeline = crumblingPipelineFor(crumblingMaterial, instanceType);
                            if (pipeline == null) {
                                continue;
                            }
                            pass.setRenderPipeline(pipeline);

                            var atlas = textureManager.getTexture(draw.material()
                                                                      .texture());
                            pass.setUniform("Sampler0", atlas.getTextureView(), atlasSampler);
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
     * {@code drawTag} and {@code type}: the last draw's values. A consumer requiring a homogeneous type splits its
     * batches through {@link #incompatibleUber}. {@code required}: the generation covering every draw.
     */
    public record UberDraw(Material material, boolean embedded, int drawTag, int start, int end,
                           @Nullable InstanceType<?> type, boolean viewDepthSorted, ProgramGeneration required) {

        static void submitRaw(int start, int end) {
            long indirect = (long) start * IndirectBuffers.DRAW_COMMAND_STRIDE;
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, indirect, end - start,
                    (int) IndirectBuffers.DRAW_COMMAND_STRIDE);
        }
    }

    record MeshDrawRun(Material material, boolean embedded, InstanceType<?> type, int start, int end,
                       ProgramGeneration required) {
    }
}
