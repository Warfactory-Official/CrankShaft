package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.shaderpack.GuestIndirectBatches.Batch;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.backend.engine.indirect.*;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedLight;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.array.GlVertexArray;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GuestIndirectDrawManager extends DrawManager<IndirectInstancer<?>> implements GuestRenderer {
    private static final ResourceLocation BLOB_SHADOW = new ResourceLocation("minecraft", "textures/misc/shadow.png");
    private final IndirectPrograms indirectPrograms;
    private final StagingBuffer staging;
    private final MeshPool meshes = new MeshPool(new GuestVertexExtras());
    private final GlVertexArray vao = GlVertexArray.create();
    private final GuestTerrainBatch terrain = new GuestTerrainBatch();
    private final SharedIndirectStorage sharedStorage = new SharedIndirectStorage();
    private final GuestIndirectBatches batches = new GuestIndirectBatches(sharedStorage);
    private final Map<InstanceType<?>, Group<?>> groups = new HashMap<>();
    private final InstancedLight light = new InstancedLight();
    private final MatrixBuffer matrices = new MatrixBuffer();
    private final DepthPyramid depth;
    private final GuestPrograms programs = new GuestPrograms();
    private final GuestPipelines mainOit = new GuestPipelines(true);
    private final GuestPipelines shadowOit = new GuestPipelines(true);
    private final List<GuestIndirectDraw> translucent = new ArrayList<>();
    private final List<RenderChunk> chunks = new ArrayList<>();
    private final GlBuffer crumbling = new GlBuffer(GlBufferUsage.STREAM_DRAW);
    private final GuestShadowCull shadowCull = new GuestShadowCull();
    private long shadowFrames;
    private long submittedBatches;
    private GuestPatchIndices patches;

    public GuestIndirectDrawManager() {
        indirectPrograms = IndirectPrograms.get();
        indirectPrograms.acquire();
        staging = new StagingBuffer(indirectPrograms);
        depth = new DepthPyramid(indirectPrograms);
        meshes.bind(vao);
    }

    private static boolean eligible(GuestRenderPass pass, GuestDraw draw) {
        return !(pass.host.suppressBlobShadows && draw.material().texture().equals(BLOB_SHADOW))
                && (!pass.shadow || draw.castsShadow(pass.host));
    }

    private static boolean early(GuestRenderPass pass, GuestDraw draw) {
        var kind = draw.kind(pass.host);
        if (TaggedEnvironment.isRoutedKind(kind)) return false;
        return draw.material().transparency() == Transparency.OPAQUE || !pass.shadow
                && kind == TaggedEnvironment.KIND_ENTITY && draw.material().transparency() == Transparency.TRANSLUCENT
                && pass.host.contracts().has(ContractProgram.GBUFFERS_ENTITIES);
    }

    @Override
    public boolean supportsRenderIdentity() {
        return true;
    }

    @Override
    protected <I extends Instance> IndirectInstancer<I> create(InstancerKey<I> key) {
        return new IndirectInstancer<>(key, new IndirectInstancer.Recreate<>(key, this));
    }

    @SuppressWarnings("unchecked")
    @Override
    protected <I extends Instance> void initialize(InstancerKey<I> key, IndirectInstancer<?> instancer) {
        var group = (Group<I>) groups.computeIfAbsent(key.type(),
                _ -> new Group<>(key.type(), indirectPrograms, sharedStorage));
        group.add((IndirectInstancer<I>) instancer, key, meshes);
    }

    @Override
    public void render(LightStorage lightStorage, EnvironmentStorage environments) {
        if (!GuestRenderPass.active()) return;
        super.render(lightStorage, environments);
        groups.values().removeIf(IndirectCullingGroup::flushInstancers);
        instancers.values().removeIf(instancer -> {
            if (instancer.instanceCount() != 0) return false;
            instancer.clear();
            return true;
        });
        meshes.flush();
        staging.reclaim();
        translucent.clear();
        if (groups.isEmpty()) {
            batches.clear();
            return;
        }
        light.flush(lightStorage);
        matrices.flush(staging, environments);
        // Shared arenas must finish relocating before any upload retains a destination handle or offset.
        for (var group : groups.values()) group.prepareUpload();
        for (var group : groups.values()) group.upload(staging);
        staging.flush();
        Uniforms.bindAll();
        try (var pass = new GuestRenderPass(false, false)) {
            programs.own(pass.host);
            mainOit.own(pass.host);
            shadowOit.own(pass.host);
            if (pass.shadow && !pass.host.contracts().properties.shadowEnabled()) return;
            if (pass.shadow) shadowFrames++;
            else {
                var texture = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                        GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                depth.regenerate(texture, GL45.glGetTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_WIDTH),
                        GL45.glGetTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_HEIGHT));
                depth.bindForCull();
            }
            matrices.bind();
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            if (pass.shadow) shadowCull.prepare(pass);
            for (var group : groups.values()) {
                if (pass.shadow) shadowCull.upload(group.cullProgram());
                group.dispatchCull(pass.shadow);
            }
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            indirectPrograms.getApplyProgram().bind();
            for (var group : groups.values()) group.dispatchApply();
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_COMMAND_BARRIER_BIT);
            if (pass.host.tessellated()) {
                if (patches == null) patches = new GuestPatchIndices();
                patches.prepare(meshes.pooledMeshes());
            }
            batches.prepare(pass.host, groups.values(), pass.host.tessellated() ? patches : null);
            vao.bindForDraw();
            for (var batch : batches.batches()) {
                var draw = batch.draw();
                if (!eligible(pass, draw)) continue;
                if (early(pass, draw)) submit(pass, batch, null, null, false);
                else translucent.add(draw);
            }
        }
    }

    @Override
    public void renderOit(LightStorage lightStorage, EnvironmentStorage environments) {
        if (!GuestRenderPass.active()) return;
        var minecraft = Minecraft.getMinecraft();
        Uniforms.bindAll();
        chunks.clear();
        var pooledTerrain = NothiriumTerrain.captured();
        try (var pass = new GuestRenderPass(true, false)) {
            if (pass.shadow && !pass.host.contracts().properties.shadowEnabled()) return;
            if (!pooledTerrain && (!pass.shadow || pass.host.settings().shadowTranslucent())) {
                for (var chunk : minecraft.renderGlobal.renderContainer.renderChunks) {
                    if (chunk.getVertexBufferByLayer(BlockRenderLayer.TRANSLUCENT.ordinal()).count > 0)
                        chunks.add(chunk);
                }
            }
            var hasTerrain = pooledTerrain ? !NothiriumTerrain.slices().isEmpty() : !chunks.isEmpty();
            if (!hasTerrain && translucent.isEmpty()) return;
            var oit = pass.shadow ? shadowOit : mainOit;
            var shared = (hasTerrain || translucent.stream().anyMatch(
                    draw -> GuestInstancedDrawManager.sharedOitMaterial(pass, draw)))
                    && oit.begin(pass, programs, translucent, hasTerrain);
            if (shared) {
                if (hasTerrain) {
                    var patches = oit.terrain(pass, OitPass.DEPTH_RANGE).tessellated;
                    if (pooledTerrain) terrain.prepareNative(NothiriumTerrain.slices(), false, patches);
                    else terrain.prepare(chunks, BlockRenderLayer.TRANSLUCENT, patches);
                }
                try {
                    for (var mode : OitPass.values()) {
                        if (mode == OitPass.COEFFICIENTS && oit.contract().ranks().length == 0) continue;
                        oit.prepare(mode);
                        if (hasTerrain) {
                            var program = oit.terrain(pass, mode);
                            program.bind(pass, 0);
                            oit.bind(program.program);
                            terrain.draw(program);
                        }
                        vao.bindForDraw();
                        for (var batch : batches.batches()) {
                            if (eligible(pass, batch.draw()) && !early(pass,
                                    batch.draw()) && GuestInstancedDrawManager.sharedOitMaterial(pass, batch.draw()))
                                submit(pass, batch, mode, oit, false);
                        }
                    }
                    oit.composite();
                } finally {
                    oit.finish();
                }
                pass.rebind();
            } else {
                if (!pass.shadow && pass.host.deferredReplay()) GlStateManager.depthMask(true);
                if (hasTerrain) terrain.drawNative(pass, chunks, BlockRenderLayer.TRANSLUCENT);
            }
            vao.bindForDraw();
            for (var batch : batches.batches()) {
                var draw = batch.draw();
                if (!eligible(pass, draw) || early(pass, draw) || shared && GuestInstancedDrawManager.sharedOitMaterial(pass,
                        draw)) continue;
                submit(pass, batch, null, null, false);
            }
            if (pass.shadow || pass.host.deferredReplay()) return;
            for (var batch : batches.batches()) {
                var draw = batch.draw();
                var material = draw.material();
                var emissive = !TaggedEnvironment.isRoutedKind(draw.kind(pass.host))
                        && (material.transparency() == Transparency.ADDITIVE || material.transparency() == Transparency.LIGHTNING);
                if (!eligible(pass, draw) || early(pass, draw) || shared && GuestInstancedDrawManager.sharedOitMaterial(pass,
                        draw)
                        || material.writeMask()
                                   .depth() || material.transparency() == Transparency.GLINT || emissive && pass.host.emissiveLight())
                    continue;
                submit(pass, batch, null, null, true);
            }
        }
    }

    private void submit(GuestRenderPass pass, Batch batch, OitPass mode, GuestPipelines oit, boolean depthOnly) {
        var draw = batch.draw();
        pass.activate(GuestInstancedDrawManager.phase(pass, draw));
        var program = mode == null ? programs.get(pass, draw, false) : programs.get(pass, draw, mode, oit.contract());
        GuestInstancedDrawManager.requireInputs(program, draw);
        pass.host.prepareMaterial();
        MaterialRenderState.setupOit(program.material(draw));
        GlStateManager.depthMask(program.material(draw).writeMask().depth());
        program.bindDraw(pass, draw, light, 0);
        if (oit != null) {
            GlStateManager.depthMask(false);
            oit.bind(program.program);
        }
        if (depthOnly) {
            GlStateManager.colorMask(false, false, false, false);
            GlStateManager.depthMask(true);
        }
        batches.bind(program.tessellated);
        vao.setElementBuffer(program.tessellated ? patches.buffer() : meshes.indexBuffer());
        vao.bindForDraw();
        matrices.bind();
        GlCompat.safeMultiDrawElementsIndirect(program.program, program.primitive(), GL11.GL_UNSIGNED_INT,
                batch.start(), batch.end(), IndirectBuffers.DRAW_COMMAND_STRIDE);
        submittedBatches++;
    }

    @Override
    public void renderCrumbling(List<Engine.CrumblingBlock> blocks) {
        if (!GuestRenderPass.active()) return;
        var sorted = doCrumblingSort(blocks, IndirectInstancer::fromState);
        if (sorted.isEmpty()) return;
        Uniforms.bindAll();
        var command = MemoryBlock.malloc(IndirectBuffers.DRAW_COMMAND_STRIDE);
        try (var pass = new GuestRenderPass(false, true)) {
            vao.bindForDraw();
            matrices.bind();
            for (var entry : sorted.entrySet()) {
                var group = groups.get(entry.getKey().instanceType());
                for (var progress : entry.getValue().int2ObjectEntrySet())
                    for (var pair : progress.getValue()) {
                        for (var raw : pair.getFirst().draws()) {
                            var draw = (GuestIndirectDraw) raw;
                            var program = programs.get(pass, draw, true);
                            pass.host.prepareMaterial();
                            MaterialRenderState.setupOit(program.material(draw));
                            program.bindDraw(pass, draw, light, 0);
                            GlStateManager.setActiveTexture(0x84C0);
                            TextureBinder.bind(CrumblingTextures.BREAKING_LOCATIONS[progress.getIntKey()]);
                            draw.writeWithOverrides(command.ptr(), pair.getSecond().index, program.material(draw));
                            if (program.tessellated) {
                                MemoryUtil.memPutInt(command.ptr(), GuestPatchIndices.count(draw.mesh()));
                                MemoryUtil.memPutInt(command.ptr() + 8, patches.first(draw.mesh()));
                            }
                            crumbling.upload(command);
                            batches.bind(program.tessellated);
                            vao.setElementBuffer(program.tessellated ? patches.buffer() : meshes.indexBuffer());
                            vao.bindForDraw();
                            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, crumbling.handle());
                            program.program.setUInt("_flw_baseDraw", 0);
                            GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, BufferBindings.DRAW,
                                    crumbling.handle(), 0, IndirectBuffers.DRAW_COMMAND_STRIDE);
                            GL40.glDrawElementsIndirect(program.primitive(), GL11.GL_UNSIGNED_INT, 0);
                        }
                    }
            }
        } finally {
            command.free();
        }
    }

    public boolean sharedOitActive() {
        return mainOit.framesRendered > 0 && (mainOit.completedPasses & 5) == 5;
    }

    public boolean authoredContractActive() {
        return programs.authoredContractActive();
    }

    public boolean shadowContractActive() {
        return shadowFrames > 0;
    }

    public boolean shadowOitActive() {
        return shadowOit.framesRendered > 0;
    }

    public long submittedBatches() {
        return submittedBatches;
    }

    public long submittedTerrainBatches() {
        return terrain.submittedBatches();
    }

    public long uploadedTerrainBytes() {
        return terrain.uploadedBytes();
    }

    public void renderNativeTerrain(BlockRenderLayer layer, List<RenderChunk> chunks) {
        var phase = switch (layer) {
            case SOLID -> GuestPhase.TERRAIN_SOLID;
            case CUTOUT -> GuestPhase.TERRAIN_CUTOUT;
            case CUTOUT_MIPPED -> GuestPhase.TERRAIN_CUTOUT_MIPPED;
            case TRANSLUCENT -> GuestPhase.TERRAIN_TRANSLUCENT;
        };
        try (var state = GlStateTracker.getRestoreState(); var pass = new GuestRenderPass(phase)) {
            terrain.drawNative(pass, chunks, layer);
        }
    }

    @Override
    public void delete() {
        instancers.values().forEach(IndirectInstancer::delete);
        super.delete();
        groups.values().forEach(IndirectCullingGroup::delete);
        groups.clear();
        batches.delete();
        sharedStorage.delete();
        if (patches != null) patches.delete();
        staging.delete();
        meshes.delete();
        vao.delete();
        terrain.delete();
        light.delete();
        matrices.delete();
        depth.delete();
        crumbling.delete();
        programs.delete();
        mainOit.delete();
        shadowOit.delete();
        indirectPrograms.release();
    }

    @Override
    public MeshPool meshPool() {
        return meshes;
    }

    @Override
    public void triggerFallback() {
        GuestBackends.disableIndirect();
        Minecraft.getMinecraft().renderGlobal.loadRenderers();
    }

    private static final class Group<I extends Instance> extends IndirectCullingGroup<I> {
        Group(InstanceType<I> type, IndirectPrograms programs, SharedIndirectStorage storage) {
            super(type, programs, storage);
            InstanceTypeIds.id(type);
        }

        @Override
        protected boolean usesStockBatches() {
            return false;
        }

        @Override
        protected IndirectDraw createDraw(IndirectInstancer<I> instancer, InstancerKey<I> key,
                                          Model.ConfiguredMesh mesh, MeshPool.PooledMesh pooled, int index) {
            return new GuestIndirectDraw(instancer, key, mesh, pooled, index);
        }
    }
}
