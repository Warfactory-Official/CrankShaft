package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compile.ProgramGeneration;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.compile.TerrainOverlayPipelines;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.embed.Environment;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectDraw;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectDrawManager;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.iris.compile.*;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.system.MemoryUtil.memPutInt;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Main HiZ phases precede deferred composites; shadow cull precedes main buffer reseeding.
 */
public class GuestIndirectDrawManager extends IndirectDrawManager implements GuestDrawManager {
    private final GuestShadowCull shadowCull = new GuestShadowCull();
    private final List<UberDraw> plainScratch = new ArrayList<>();
    private Vec3i frameOrigin = Vec3i.ZERO;
    private boolean frameConstantAmbientLight;
    private final List<UberDraw> forwardScratch = new ArrayList<>();
    private final List<UberDraw> captureScratch = new ArrayList<>();
    private final List<UberDraw> flattenScratch = new ArrayList<>();
    private final List<UberDraw> translucentScratch = new ArrayList<>();
    private final List<UberDraw> orderIndependentScratch = new ArrayList<>();
    private final List<UberDraw> depthFillScratch = new ArrayList<>();
    private final List<UberDraw> blockScratch = new ArrayList<>();
    private final List<UberDraw> blockEntityScratch = new ArrayList<>();
    private final List<UberDraw> borrowedScratch = new ArrayList<>();
    private final List<UberDraw> entityScratch = new ArrayList<>();
    private final List<UberDraw> eyesScratch = new ArrayList<>();
    private final List<UberDraw> translucentEntityScratch = new ArrayList<>();
    private final List<UberDraw> blendedEntityScratch = new ArrayList<>();
    private final List<UberDraw> particleScratch = new ArrayList<>();
    private final List<UberDraw> lateLightScratch = new ArrayList<>();
    private final List<UberDraw> immediateAdditiveScratch = new ArrayList<>();
    private final List<UberDraw> sortedBlendScratch = new ArrayList<>();
    private final GlBuffer sortedBlendRanges = new GlBuffer(GlBufferUsage.STREAM_DRAW);
    private final Function<UberDraw, RenderPipeline> sortedBlendFastPipeline =
            batch -> sortedBlendPipeline(batch, SortedBlendPass.FAST);
    private final Function<UberDraw, RenderPipeline> sortedBlendFallbackPipeline =
            batch -> sortedBlendPipeline(batch, SortedBlendPass.FALLBACK);
    private int drawKind;
    /**
     * Pipelines drawing the current batches in other passes of one frame: every pass draws what all of them cover.
     */
    private List<Function<UberDraw, RenderPipeline>> coverageGroup = List.of();
    private boolean passEntities = true;
    private boolean passBlockEntities = true;
    private boolean shadowPass;
    private boolean hasDraws;
    private @Nullable SodiumTerrainOitReplay guestTerrain;

    public GuestIndirectDrawManager(IndirectPrograms programs) {
        super(programs);
    }

    @Override
    public void prepareFrame(LightStorage lightStorage, EnvironmentStorage environmentStorage, Matrix4fc modelView,
                             Vec3i renderOrigin, boolean constantAmbientLight) {
        sortedBlendScratch.clear();
        lateLightScratch.clear();
        frameOrigin = renderOrigin;
        frameConstantAmbientLight = constantAmbientLight;
        hasDraws = prepare(lightStorage, environmentStorage, modelView, renderOrigin, constantAmbientLight);
        if (bindlessTextures()) {
            GuestTextureInfo.refresh(Minecraft.getInstance().getTextureManager());
        }
    }

    @Override
    public void drawOpaque(IrisRenderingPipeline pipeline) {
        if (!hasDraws) {
            TerrainDrawDispatcher.runDeferredPostVisuals();
            return;
        }
        submitMain(renderModelView);
        submitPass2IfPending();
        if (GuestPipelines.deferredEmissive(pipeline)) {
            submitAdditive("flywheel:iris/additive_gbuffer");
        }
    }

    @Override
    public boolean drawShadow(IrisRenderingPipeline _, Matrix4fc shadowModelView, Vec3i renderOrigin,
                              Vec3 camera, boolean entities, boolean blockEntities) {
        if (!hasDraws) {
            return false;
        }
        InstanceTypeIds.Snapshot cull = shadowCull.prepare(renderOrigin, camera);
        if (cull == null) {
            return false;
        }
        GlCompat.pushDebugGroup("flywheel:iris/shadow");
        cullIntoPass2(shadowCull.program(cull), cull.types().size());
        passEntities = entities;
        passBlockEntities = blockEntities;
        shadowPass = true;
        submitUberPass("flywheel:iris/shadow", uberMultiDraws, shadowModelView, true, shadowPipeline());
        shadowPass = false;
        passEntities = passBlockEntities = true;
        GlCompat.popDebugGroup();
        return true;
    }

    @Override
    public void drawShadowTranslucent(IrisRenderingPipeline pipeline, Matrix4fc shadowModelView, boolean entities,
                                      boolean blockEntities) {
        GlCompat.pushDebugGroup("flywheel:iris/shadow_translucent");
        passEntities = entities;
        passBlockEntities = blockEntities;
        shadowPass = true;
        submitTranslucent(pipeline, true, shadowModelView, true, shadowPipeline());
        shadowPass = false;
        passEntities = passBlockEntities = true;
        GlCompat.popDebugGroup();
    }

    @Override
    public boolean renderOit(LightStorage _, EnvironmentStorage _,
                             @Nullable ChunkSectionsToRender _, @Nullable BerTranslucentCapture _,
                             #if MC < 26.3 @Nullable FabulousCaptures _, #endif
                             @Nullable SodiumTerrainOitReplay terrain) {
        if (IrisApi.getInstance()
                   .isRenderingShadowPass()) {
            return false;
        }
        IrisRenderingPipeline pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        if (!hasDraws && terrain == null && !FarLayerMainGuest.pending()) {
            CaptureSnapshot.take(pipeline);
            return false;
        }
        try {
            if (terrain != null && !GuestPipelines.oitActive(pipeline, false) && !GuestPipelines.deferredOitActive(
                    pipeline)) {
                terrain = null;
            }
            if (terrain != null) {
                RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
                GpuTextureView depthView = target.getDepthTextureView();
                if (depthView == null) {
                    return false;
                }
                terrain.prepareCull(depthView, target.width, target.height, false);
            }
            GlCompat.pushDebugGroup("flywheel:iris/translucent");
            try {
                guestTerrain = terrain;
                submitTranslucent(pipeline, false, renderModelView, false,
                        batch -> mainTranslucentPipeline(pipeline, batch));
                guestTerrain = null;
                if (!GuestPipelines.deferredEmissive(pipeline)) {
                    submitAdditive(pipeline, "flywheel:iris/translucent_additive");
                }
                submitDepthFill(pipeline);
                return terrain != null;
            } finally {
                GlCompat.popDebugGroup();
            }
        } finally {
            GuestSsbos.restore(pipeline);
        }
    }

    @Override
    public void renderCrumbling(List<Engine.CrumblingBlock> crumblingBlocks) {
        GuestProgram.setModelView(renderModelView);
        IrisRenderingPipeline pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        try {
            GuestSsbos.bindForGuest(pipeline);
            if (GuestPipelines.velocityDemanded()) {
                bindHistory();
            }
            super.renderCrumbling(crumblingBlocks);
        } finally {
            GuestSsbos.restore(pipeline);
        }
    }

    private RenderPipeline mainTranslucentPipeline(IrisRenderingPipeline pipeline, UberDraw batch) {
        InstanceType<?> type = null;
        if (GuestPipelines.orderedBillboardCapture(pipeline) && batch.type() == InstanceTypes.BILLBOARD) {
            type = InstanceTypes.BILLBOARD;
        }
        return GuestPipelines.indirect(role(PackRole.TRANSLUCENT), batch.material(), type, batch.embedded());
    }

    private void submitDepthFill(IrisRenderingPipeline pipeline) {
        depthFillScratch.clear();
        if (!GuestPipelines.deferredTranslucent(pipeline) && !GuestPipelines.deferredOitActive(pipeline)) {
            boolean oit = GuestPipelines.oitActive(pipeline, false);
            for (UberDraw batch : uberOitMultiDraws) {
                if (batch.material().transparency() != Transparency.GLINT && !batch.material()
                                                                                   .writeMask()
                                                                                   .depth() && !(oit && GuestDrawManager.orderIndependent(
                        batch.material(),
                        batch.drawTag())) && !GuestDrawManager.depthless(batch.material(), batch.drawTag())
                        && !GuestDrawManager.translucentParticle(batch.drawTag())) {
                    depthFillScratch.add(batch);
                }
            }
        }
        if (!GuestPipelines.deferredEmissive(pipeline) && !GuestPipelines.emissiveLight(pipeline)) {
            for (UberDraw batch : uberOitAdditiveMultiDraws) {
                if (!batch.material()
                          .writeMask()
                          .depth()) {
                    depthFillScratch.add(batch);
                }
            }
        }
        // Depth only where the colour pass draws.
        submitGroup("flywheel:iris/depth_fill", depthFillScratch, renderModelView, false,
                List.of(batch -> GuestPipelines.indirectDepthFill(role(GuestDrawManager.emissive(batch.material())
                                ? PackRole.ADDITIVE : PackRole.TRANSLUCENT), batch.material(), batch.embedded()),
                        batch -> GuestDrawManager.emissive(batch.material())
                                ? GuestPipelines.indirect(role(PackRole.ADDITIVE), batch.material(), batch.embedded())
                                : mainTranslucentPipeline(pipeline, batch)), 0);
    }

    /**
     * Pass {@code drawn} of {@code group}, limited to what every pass covers.
     */
    private void submitGroup(String label, List<UberDraw> batches, Matrix4fc modelView, boolean pass2,
                             List<Function<UberDraw, RenderPipeline>> group, int drawn) {
        coverageGroup = group;
        try {
            submitUberPass(label, batches, modelView, pass2, group.get(drawn));
        } finally {
            coverageGroup = List.of();
        }
    }

    @Override
    protected ProgramGeneration coverage(UberDraw batch, RenderPipeline pipeline) {
        ProgramGeneration coverage = super.coverage(batch, pipeline);
        for (var member : coverageGroup) {
            RenderPipeline other = member.apply(batch);
            if (other == null) return ProgramGeneration.NONE;
            coverage = coverage.min(super.coverage(batch, other));
        }
        return coverage;
    }

    private void submitTranslucent(IrisRenderingPipeline pipeline, boolean shadow, Matrix4fc modelView, boolean pass2,
                                   Function<UberDraw, RenderPipeline> plainPipeline) {
        String prefix = shadow ? "flywheel:iris/shadow_" : "flywheel:iris/";
        List<UberDraw> draws = uberOitMultiDraws;
        flattenScratch.clear();
        if (!shadow && GuestPipelines.singleRecordParticles(pipeline)) {
            draws = translucentScratch;
            draws.clear();
            for (UberDraw batch : uberOitMultiDraws) {
                (GuestDrawManager.translucentParticle(batch.drawTag()) ? flattenScratch : draws).add(batch);
            }
        }
        if (!shadow && GuestPipelines.deferredOitActive(pipeline)) {
            // Routed draws take the pack's entity programs, which never capture: they reach the snapshot.
            forwardScratch.clear();
            captureScratch.clear();
            for (UberDraw batch : draws) {
                (GuestDrawManager.routed(batch.drawTag()) ? forwardScratch : captureScratch).add(batch);
            }
            submitUberPass(prefix + "translucent_forward", forwardScratch, modelView, pass2, plainPipeline);
            submitParticleHandoff(pipeline, modelView, pass2);
            CaptureSnapshot.take(pipeline);
            if (canDeferSortedBillboard(pipeline)) {
                sortedBlendScratch.add(captureScratch.getFirst());
                captureScratch.clear();
                LateLightCapture.schedule(this);
            }
            submitUberPass(prefix + "translucent_capture", captureScratch, modelView, pass2, plainPipeline);
            if (!shadow) FarLayerMainGuest.draw(prefix + "far_layers", null, modelView, frameOrigin,
                    frameConstantAmbientLight);
            if (guestTerrain != null) {
                guestTerrain.replayGuest(TerrainOverlayPipelines.guestOitPipeline(3), 3,
                        Minecraft.getInstance().gameRenderer.lightmap(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            }
            return;
        }
        if (!GuestPipelines.oitActive(pipeline, shadow)) {
            submitUberPass(prefix + "translucent", draws, modelView, pass2, plainPipeline);
            if (!shadow) FarLayerMainGuest.draw(prefix + "far_layers", null, modelView, frameOrigin,
                    frameConstantAmbientLight);
            submitParticleHandoff(pipeline, modelView, pass2);
            return;
        }
        plainScratch.clear();
        orderIndependentScratch.clear();
        for (UberDraw batch : draws) {
            (GuestDrawManager.orderIndependent(batch.material(), batch.drawTag()) ? orderIndependentScratch
                    : plainScratch).add(batch);
        }
        submitUberPass(prefix + "translucent", plainScratch, modelView, pass2, plainPipeline);
        submitParticleHandoff(pipeline, modelView, pass2);
        SodiumTerrainOitReplay terrain = shadow ? null : guestTerrain;
        boolean farLayers = !shadow && FarLayerMainGuest.pending();
        if (orderIndependentScratch.isEmpty() && terrain == null && !farLayers) {
            return;
        }
        PackRole role = shadow ? PackRole.SHADOW : PackRole.TRANSLUCENT;
        boolean coefficients = GuestPipelines.prepareOit(pipeline, shadow);
        List<Function<UberDraw, RenderPipeline>> oit = new ArrayList<>(3);
        for (OitPass pass : coefficients ? OitPass.values() : new OitPass[]{OitPass.DEPTH_RANGE, OitPass.EVALUATE}) {
            oit.add(batch -> GuestPipelines.indirectOit(role(role), pass, batch.material(), batch.embedded()));
        }
        submitGroup(prefix + "oit_depth_range", orderIndependentScratch, modelView, pass2, oit, 0);
        replayTerrain(terrain, OitPass.DEPTH_RANGE);
        if (farLayers) FarLayerMainGuest.draw(prefix + "far_layers_depth_range", OitPass.DEPTH_RANGE, modelView,
                frameOrigin, frameConstantAmbientLight);
        if (coefficients) {
            submitGroup(prefix + "oit_coefficients", orderIndependentScratch, modelView, pass2, oit, 1);
            replayTerrain(terrain, OitPass.COEFFICIENTS);
            if (farLayers) FarLayerMainGuest.draw(prefix + "far_layers_coefficients", OitPass.COEFFICIENTS,
                    modelView, frameOrigin, frameConstantAmbientLight);
        }
        submitGroup(prefix + "oit_evaluate", orderIndependentScratch, modelView, pass2, oit, oit.size() - 1);
        replayTerrain(terrain, OitPass.EVALUATE);
        if (farLayers) FarLayerMainGuest.draw(prefix + "far_layers_evaluate", OitPass.EVALUATE, modelView,
                frameOrigin, frameConstantAmbientLight);
        GuestOitComposite.draw(prefix + "oit_composite", shadow);
    }

    private void submitParticleHandoff(IrisRenderingPipeline pipeline, Matrix4fc modelView, boolean pass2) {
        if (flattenScratch.isEmpty()) return;
        GuestPipelines.prepareFlatten(pipeline);
        List<Function<UberDraw, RenderPipeline>> flatten = List.of(
                batch -> GuestPipelines.indirect(PackRole.PARTICLES_FLATTEN, batch.material(), batch.embedded()),
                batch -> GuestPipelines.indirect(PackRole.PARTICLES_HANDOFF, batch.material(), batch.embedded()));
        submitGroup("flywheel:iris/particle_flatten", flattenScratch, modelView, pass2, flatten, 0);
        submitGroup("flywheel:iris/particle_handoff", flattenScratch, modelView, pass2, flatten, 1);
        flattenScratch.clear();
    }

    private void replayTerrain(@Nullable SodiumTerrainOitReplay terrain, OitPass pass) {
        if (terrain == null) {
            return;
        }
        terrain.replayGuest(TerrainOverlayPipelines.guestOitPipeline(pass.ordinal()), pass.ordinal(),
                Minecraft.getInstance().gameRenderer.lightmap(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }

    private boolean canDeferSortedBillboard(IrisRenderingPipeline pipeline) {
        if (!Boolean.parseBoolean(System.getProperty("crankshaft.iris.sortedBillboardBlendActive", "true")))
            return false;
        if (captureScratch.size() != 1) return false;
        var batch = captureScratch.getFirst();
        if (batch.end() - batch.start() != 1 || batch.type() != InstanceTypes.BILLBOARD
                || !batch.viewDepthSorted() || batch.embedded() || batch.material().writeMask().depth()
                || !batch.material().writeMask().color()) return false;
        var role = PackRole.TRANSLUCENT.forKind(TaggedEnvironment.kind(batch.drawTag()));
        return GuestPipelines.sortedBillboardBlendEligible(pipeline, role, batch.material());
    }

    @Override
    public void drawLateBillboards() {
        if (sortedBlendScratch.isEmpty()) return;
        var pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        int rangeCount = uberOitAdditiveMultiDraws.size();
        try (var stack = MemoryStack.stackPush()) {
            long ranges = stack.nmalloc(4, Math.max(8, rangeCount * 8));
            for (int index = 0; index < rangeCount; index++) {
                var batch = uberOitAdditiveMultiDraws.get(index);
                memPutInt(ranges + (long) index * 8, batch.start());
                memPutInt(ranges + (long) index * 8 + 4, batch.end());
            }
            sortedBlendRanges.upload(ranges, Math.max(8, rangeCount * 8));
        }
        GuestPipelines.prepareSortedBillboardBlend(pipeline, drawCommandsBuffer(), sortedBlendRanges.handle(),
                rangeCount);
        List<Function<UberDraw, RenderPipeline>> sorted = List.of(sortedBlendFastPipeline,
                sortedBlendFallbackPipeline);
        submitGroup("flywheel:iris/sorted_billboard_fast", sortedBlendScratch, renderModelView, false, sorted, 0);
        submitGroup("flywheel:iris/sorted_billboard_fallback", sortedBlendScratch, renderModelView, false, sorted, 1);
        GuestPipelines.finishSortedBillboardBlend(pipeline);
        sortedBlendScratch.clear();
    }

    private RenderPipeline sortedBlendPipeline(UberDraw batch, SortedBlendPass pass) {
        return GuestPipelines.indirectSortedBillboard(role(PackRole.TRANSLUCENT), batch.material(), batch.embedded(),
                pass);
    }

    @Override
    protected void submitUberPass(String label, List<UberDraw> batches, Matrix4fc modelViewMatrix, boolean pass2,
                                  Function<UberDraw, RenderPipeline> pipelineFor) {
        if (batches.isEmpty()) {
            return;
        }
        GuestSsbos.bindForGuest((IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable());
        if (bindlessTextures()) {
            GuestTextureInfo.bind();
        }
        if (GuestPipelines.velocityDemanded()) {
            bindHistory();
        }
        GuestProgram.setModelView(modelViewMatrix);
        blockScratch.clear();
        blockEntityScratch.clear();
        borrowedScratch.clear();
        entityScratch.clear();
        eyesScratch.clear();
        translucentEntityScratch.clear();
        blendedEntityScratch.clear();
        particleScratch.clear();
        boolean dropBlobShadows = GuestEntityShadows.suppressed();
        for (UberDraw batch : batches) {
            if (shadowPass && GuestPipelines.nativeGlint() && batch.material().transparency() == Transparency.GLINT)
                continue;
            if (shadowPass && (GuestDrawManager.emissive(batch.material())
                    || GuestDrawManager.depthless(batch.material(), batch.drawTag())
                    || GuestDrawManager.translucentParticle(batch.drawTag()))) continue;
            if (dropBlobShadows && GuestEntityShadows.isBlobShadow(batch.material())) {
                continue;
            }
            switch (TaggedEnvironment.kind(batch.drawTag())) {
                case TaggedEnvironment.KIND_ENTITY -> entityScratch.add(batch);
                case TaggedEnvironment.KIND_ENTITY_EYES -> eyesScratch.add(batch);
                case TaggedEnvironment.KIND_ENTITY_TRANSLUCENT -> translucentEntityScratch.add(batch);
                case TaggedEnvironment.KIND_ENTITY_BLENDED -> blendedEntityScratch.add(batch);
                case TaggedEnvironment.KIND_ENTITY_PARTICLES_TRANSLUCENT -> particleScratch.add(batch);
                case TaggedEnvironment.KIND_BLOCK_ENTITY -> blockEntityScratch.add(batch);
                case TaggedEnvironment.KIND_BORROWED_BLOCK -> borrowedScratch.add(batch);
                default -> blockScratch.add(batch);
            }
        }
        submitKind(label, blockScratch, 0, modelViewMatrix, pass2, pipelineFor);
        submitKind(label + "_borrowed_blocks", borrowedScratch, TaggedEnvironment.KIND_BORROWED_BLOCK,
                modelViewMatrix, pass2, pipelineFor);
        if (passBlockEntities) {
            submitKind(label + "_block_entities", blockEntityScratch, TaggedEnvironment.KIND_BLOCK_ENTITY,
                    modelViewMatrix, pass2, pipelineFor);
        }
        if (passEntities) {
            submitKind(label + "_entities", entityScratch, TaggedEnvironment.KIND_ENTITY, modelViewMatrix, pass2,
                    pipelineFor);
            submitKind(label + "_translucent_entities", translucentEntityScratch,
                    TaggedEnvironment.KIND_ENTITY_TRANSLUCENT, modelViewMatrix, pass2, pipelineFor);
            submitKind(label + "_eyes", eyesScratch, TaggedEnvironment.KIND_ENTITY_EYES, modelViewMatrix, pass2,
                    pipelineFor);
            submitKind(label + "_particles", particleScratch, TaggedEnvironment.KIND_ENTITY_PARTICLES_TRANSLUCENT,
                    modelViewMatrix, pass2, pipelineFor);
            submitKind(label + "_blended_entities", blendedEntityScratch, TaggedEnvironment.KIND_ENTITY_BLENDED,
                    modelViewMatrix, pass2, pipelineFor);
        }
    }

    private void submitKind(String label, List<UberDraw> batches, int kind, Matrix4fc modelViewMatrix, boolean pass2,
                            Function<UberDraw, RenderPipeline> pipelineFor) {
        if (batches.isEmpty()) {
            return;
        }
        drawKind = kind;
        super.submitUberPass(label, batches, modelViewMatrix, pass2, pipelineFor);
        drawKind = 0;
    }

    private PackRole role(PackRole role) {
        return role.forKind(drawKind);
    }

    private void submitAdditive(String label) {
        submitUberPass(label, uberOitAdditiveMultiDraws, renderModelView, false,
                batch -> GuestPipelines.indirect(role(PackRole.ADDITIVE), batch.material(), batch.embedded()));
    }

    private void submitAdditive(IrisRenderingPipeline pipeline, String label) {
        if (!GuestPipelines.lateLightCapture(pipeline)) {
            submitAdditive(label);
            return;
        }
        lateLightScratch.clear();
        immediateAdditiveScratch.clear();
        for (var batch : uberOitAdditiveMultiDraws)
            (GuestDrawManager.lateLight(batch.material()) ? lateLightScratch : immediateAdditiveScratch).add(batch);
        if (!lateLightScratch.isEmpty()) LateLightCapture.schedule(this);
        submitUberPass(label, immediateAdditiveScratch, renderModelView, false,
                batch -> GuestPipelines.indirect(role(PackRole.ADDITIVE), batch.material(), batch.embedded()));
    }

    @Override
    public void drawLateLight() {
        submitUberPass("flywheel:iris/light_capture", lateLightScratch, renderModelView, false,
                batch -> GuestPipelines.indirectLightCapture(role(PackRole.ADDITIVE), batch.material(),
                        batch.embedded()));
    }

    @Override
    protected DrawTags drawTags(Environment environment, Model model, Material material, Mesh mesh) {
        return GuestDrawTags.of(environment, model, material, mesh);
    }

    @Override
    protected boolean keepsHistory() {
        return GuestPipelines.velocityDemanded();
    }

    @Override
    protected boolean drawnInTranslucentPass(IndirectDraw draw) {
        return GuestDrawManager.drawnInTranslucentPass(draw.material(), draw.drawTag());
    }

    @Override
    protected boolean drawnInAdditivePass(IndirectDraw draw) {
        return GuestDrawManager.drawnInAdditivePass(draw.material(), draw.drawTag());
    }

    @Override
    protected boolean keepsSubmissionOrder(IndirectDraw draw) {
        return GuestDrawManager.keepsSubmissionOrder(draw.material(), draw.drawTag());
    }

    @Override
    protected boolean incompatibleUber(IndirectDraw a, IndirectDraw b) {
        return super.incompatibleUber(a, b) || a.material()
                                                .cutout() != b.material()
                                                              .cutout()
                || TaggedEnvironment.kind(a.drawTag()) != TaggedEnvironment.kind(b.drawTag())
                || (a.instanceType() == InstanceTypes.BILLBOARD) != (b.instanceType() == InstanceTypes.BILLBOARD);
    }

    @Override
    protected void submitRun(RenderPipeline pipeline, int start, int end) {
        if (!GuestPipelines.orderedBillboardCapture(pipeline)) {
            super.submitRun(pipeline, start, end);
            return;
        }
        GlCompat.pushDebugGroup("flywheel:iris/billboard_capture");
        try {
            super.submitRun(pipeline, start, end);
        } finally {
            GlCompat.popDebugGroup();
        }
    }

    @Override
    protected boolean bindlessPipeline(RenderPipeline pipeline) {
        return GuestPipelines.bindless(pipeline);
    }

    @Override
    protected @Nullable RenderPipeline uberPipelineFor(Material material, boolean embedded) {
        return GuestPipelines.indirect(role(PackRole.SOLID), material, embedded);
    }

    @Override
    protected void warmUp(Material _, boolean _) {
    }

    @Override
    protected @Nullable RenderPipeline crumblingPipelineFor(Material crumblingMaterial, InstanceType<?> type) {
        return GuestPipelines.crumbling(crumblingMaterial, type, true);
    }

    @Override
    public void triggerFallback() {
        Minecraft.getInstance().levelExtractor.allChanged();
    }

    @Override
    public boolean isShaderPackGuest() {
        return true;
    }

    @Override
    public void delete() {
        sortedBlendRanges.delete();
        shadowCull.delete();
        super.delete();
    }

    private Function<UberDraw, RenderPipeline> shadowPipeline() {
        return batch -> GuestPipelines.indirect(role(PackRole.SHADOW), batch.material(), batch.embedded());
    }
}
