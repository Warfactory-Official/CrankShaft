package dev.engine_room.flywheel.iris.engine;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.compile.InstancingPrograms;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.embed.Environment;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedDraw;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedDrawManager;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.iris.compile.*;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Solid: post-opaque seam; translucent: translucent seam.
 */
public class GuestInstancedDrawManager extends InstancedDrawManager implements GuestDrawManager {
    private final List<InstancedDraw> plainScratch = new ArrayList<>();
    private Vec3i frameOrigin = Vec3i.ZERO;
    private boolean frameConstantAmbientLight;
    private final List<InstancedDraw> forwardScratch = new ArrayList<>();
    private final List<InstancedDraw> captureScratch = new ArrayList<>();
    private final List<InstancedDraw> flattenScratch = new ArrayList<>();
    private final List<InstancedDraw> translucentScratch = new ArrayList<>();
    private final List<InstancedDraw> orderIndependentScratch = new ArrayList<>();
    private final List<InstancedDraw> depthFillScratch = new ArrayList<>();
    private final List<InstancedDraw> blockScratch = new ArrayList<>();
    private final List<InstancedDraw> blockEntityScratch = new ArrayList<>();
    private final List<InstancedDraw> borrowedScratch = new ArrayList<>();
    private final List<InstancedDraw> entityScratch = new ArrayList<>();
    private final List<InstancedDraw> eyesScratch = new ArrayList<>();
    private final List<InstancedDraw> translucentEntityScratch = new ArrayList<>();
    private final List<InstancedDraw> blendedEntityScratch = new ArrayList<>();
    private final List<InstancedDraw> particleScratch = new ArrayList<>();
    private final List<InstancedDraw> lateLightScratch = new ArrayList<>();
    private final List<InstancedDraw> immediateAdditiveScratch = new ArrayList<>();
    private int drawKind;
    private boolean passEntities = true;
    private boolean passBlockEntities = true;
    private boolean shadowPass;

    public GuestInstancedDrawManager(InstancingPrograms programs) {
        super(programs);
    }

    private static int drawTag(InstancedDraw draw) {
        DrawTags tags = draw.tags();
        return tags == null ? 0 : tags.drawTag();
    }

    @Override
    public void prepareFrame(LightStorage lightStorage, EnvironmentStorage environmentStorage, Matrix4fc modelView,
                             Vec3i renderOrigin, boolean constantAmbientLight) {
        frameOrigin = renderOrigin;
        frameConstantAmbientLight = constantAmbientLight;
        prepare(lightStorage, environmentStorage, modelView, renderOrigin, constantAmbientLight);
    }

    @Override
    public void drawOpaque(IrisRenderingPipeline pipeline) {
        submitOpaque();
        if (GuestPipelines.deferredEmissive(pipeline)) {
            submitPass("flywheel:iris/additive_gbuffer", oitAdditiveDraws, renderModelView, this::additivePipeline);
        }
    }

    @Override
    public boolean drawShadow(IrisRenderingPipeline _, Matrix4fc shadowModelView, Vec3i _,
                              Vec3 _, boolean entities, boolean blockEntities) {
        GlCompat.pushDebugGroup("flywheel:iris/shadow");
        passEntities = entities;
        passBlockEntities = blockEntities;
        shadowPass = true;
        if (!draws.isEmpty()) {
            submitPass("flywheel:iris/shadow", draws, shadowModelView, this::shadowPipeline);
        }
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
        submitTranslucent(pipeline, true, shadowModelView, oitDraws, this::shadowPipeline);
        shadowPass = false;
        passEntities = passBlockEntities = true;
        GlCompat.popDebugGroup();
    }

    @Override
    public boolean renderOit(LightStorage _, EnvironmentStorage _,
                             @Nullable ChunkSectionsToRender _, @Nullable BerTranslucentCapture _,
                             #if MC < 26.3 @Nullable FabulousCaptures _, #endif
                             @Nullable SodiumTerrainOitReplay _) {
        if (IrisApi.getInstance()
                   .isRenderingShadowPass()) {
            return false;
        }
        IrisRenderingPipeline pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        GlCompat.pushDebugGroup("flywheel:iris/translucent");
        try {
            List<InstancedDraw> translucent = oitDraws;
            flattenScratch.clear();
            if (GuestPipelines.singleRecordParticles(pipeline)) {
                translucent = translucentScratch;
                translucent.clear();
                for (InstancedDraw draw : oitDraws) {
                    (GuestDrawManager.translucentParticle(drawTag(draw)) ? flattenScratch : translucent).add(draw);
                }
            }
            if (GuestPipelines.deferredOitActive(pipeline)) {
                // Routed draws take the pack's entity programs, which never capture: they reach the snapshot.
                forwardScratch.clear();
                captureScratch.clear();
                for (InstancedDraw draw : translucent) {
                    (GuestDrawManager.routed(drawTag(draw)) ? forwardScratch : captureScratch).add(draw);
                }
                submitPass("flywheel:iris/translucent_forward", forwardScratch, renderModelView,
                        this::translucentPipeline);
                submitParticleHandoff(pipeline);
                CaptureSnapshot.take(pipeline);
                translucent = captureScratch;
            }
            submitTranslucent(pipeline, false, renderModelView, translucent, this::translucentPipeline);
            if (GuestPipelines.deferredOitActive(pipeline)) {
                FarLayerMainGuest.draw("flywheel:iris/far_layers", null, renderModelView, frameOrigin,
                        frameConstantAmbientLight);
            }
            if (!GuestPipelines.deferredEmissive(pipeline)) {
                submitAdditive(pipeline);
            }
            submitDepthFill(pipeline);
            return false;
        } finally {
            GlCompat.popDebugGroup();
            GuestSsbos.restore(pipeline);
        }
    }

    private void submitDepthFill(IrisRenderingPipeline pipeline) {
        depthFillScratch.clear();
        if (!GuestPipelines.deferredTranslucent(pipeline)) {
            boolean oit = GuestPipelines.oitActive(pipeline, false);
            for (InstancedDraw draw : oitDraws) {
                if (draw.material().transparency() != Transparency.GLINT && !draw.material()
                                                                                 .writeMask()
                                                                                 .depth() && !(oit && GuestDrawManager.orderIndependent(
                        draw.material(), drawTag(draw)))
                        && !GuestDrawManager.depthless(draw.material(), drawTag(draw))
                        && !GuestDrawManager.translucentParticle(drawTag(draw))) {
                    depthFillScratch.add(draw);
                }
            }
        }
        if (!GuestPipelines.deferredEmissive(pipeline) && !GuestPipelines.emissiveLight(pipeline)) {
            for (InstancedDraw draw : oitAdditiveDraws) {
                if (!draw.material()
                         .writeMask()
                         .depth()) {
                    depthFillScratch.add(draw);
                }
            }
        }
        // Depth only where the colour pass draws.
        submitPass("flywheel:iris/depth_fill", depthFillScratch, renderModelView, group(List.of(
                        (material, type, embedded) -> GuestPipelines.instancingDepthFill(
                                role(GuestDrawManager.emissive(material) ? PackRole.ADDITIVE : PackRole.TRANSLUCENT), material,
                                type, embedded),
                        (material, type, embedded) -> GuestDrawManager.emissive(material)
                                ? additivePipeline(material, type, embedded) : translucentPipeline(material, type, embedded)),
                0));
    }

    /**
     * Pass {@code drawn} of passes drawing the same draws in one frame: a draw waits for every pass's pipeline.
     */
    private static PipelineSelector group(List<PipelineSelector> passes, int drawn) {
        return (material, type, embedded) -> {
            RenderPipeline pipeline = null;
            for (int i = 0; i < passes.size(); i++) {
                RenderPipeline member = passes.get(i).pipeline(material, type, embedded);
                if (member == null) return null;
                if (i == drawn) pipeline = member;
            }
            return pipeline;
        };
    }

    @Override
    public void drawLateLight() {
        submitPass("flywheel:iris/light_capture", lateLightScratch, renderModelView,
                (material, type, embedded) -> GuestPipelines.instancingLightCapture(role(PackRole.ADDITIVE),
                        material, type, embedded));
    }

    private void submitAdditive(IrisRenderingPipeline pipeline) {
        if (!GuestPipelines.lateLightCapture(pipeline)) {
            submitPass("flywheel:iris/translucent_additive", oitAdditiveDraws, renderModelView,
                    this::additivePipeline);
            return;
        }
        lateLightScratch.clear();
        immediateAdditiveScratch.clear();
        for (var draw : oitAdditiveDraws)
            (GuestDrawManager.lateLight(draw.material()) ? lateLightScratch : immediateAdditiveScratch).add(draw);
        if (!lateLightScratch.isEmpty()) LateLightCapture.schedule(this);
        submitPass("flywheel:iris/translucent_additive", immediateAdditiveScratch, renderModelView,
                this::additivePipeline);
    }

    private void submitTranslucent(IrisRenderingPipeline pipeline, boolean shadow, Matrix4fc modelView,
                                   List<InstancedDraw> draws, PipelineSelector plainPipeline) {
        String prefix = shadow ? "flywheel:iris/shadow_" : "flywheel:iris/";
        // Deferred capture draws its far layers after this, at the capture seam.
        boolean farLayers = !shadow && !GuestPipelines.deferredOitActive(pipeline) && FarLayerMainGuest.pending();
        if (!GuestPipelines.oitActive(pipeline, shadow)) {
            submitPass(prefix + "translucent", draws, modelView, plainPipeline);
            if (farLayers) FarLayerMainGuest.draw(prefix + "far_layers", null, modelView, frameOrigin,
                    frameConstantAmbientLight);
            if (!shadow) submitParticleHandoff(pipeline);
            return;
        }
        plainScratch.clear();
        orderIndependentScratch.clear();
        for (InstancedDraw draw : draws) {
            (GuestDrawManager.orderIndependent(draw.material(), drawTag(draw)) ? orderIndependentScratch
                    : plainScratch).add(draw);
        }
        submitPass(prefix + "translucent", plainScratch, modelView, plainPipeline);
        if (!shadow) submitParticleHandoff(pipeline);
        if (orderIndependentScratch.isEmpty() && !farLayers) {
            return;
        }
        PackRole role = shadow ? PackRole.SHADOW : PackRole.TRANSLUCENT;
        boolean coefficients = GuestPipelines.prepareOit(pipeline, shadow);
        List<PipelineSelector> oit = new ArrayList<>(3);
        for (OitPass pass : coefficients ? OitPass.values() : new OitPass[]{OitPass.DEPTH_RANGE, OitPass.EVALUATE}) {
            oit.add((material, type, embedded) -> GuestPipelines.instancingOit(role(role), pass, material, type,
                    embedded));
        }
        submitPass(prefix + "oit_depth_range", orderIndependentScratch, modelView, group(oit, 0));
        if (farLayers) FarLayerMainGuest.draw(prefix + "far_layers_depth_range", OitPass.DEPTH_RANGE, modelView,
                frameOrigin, frameConstantAmbientLight);
        if (coefficients) {
            submitPass(prefix + "oit_coefficients", orderIndependentScratch, modelView, group(oit, 1));
            if (farLayers) FarLayerMainGuest.draw(prefix + "far_layers_coefficients", OitPass.COEFFICIENTS,
                    modelView, frameOrigin, frameConstantAmbientLight);
        }
        submitPass(prefix + "oit_evaluate", orderIndependentScratch, modelView, group(oit, oit.size() - 1));
        if (farLayers) FarLayerMainGuest.draw(prefix + "far_layers_evaluate", OitPass.EVALUATE, modelView,
                frameOrigin, frameConstantAmbientLight);
        GuestOitComposite.draw(prefix + "oit_composite", shadow);
    }

    private void submitParticleHandoff(IrisRenderingPipeline pipeline) {
        if (flattenScratch.isEmpty()) return;
        GuestPipelines.prepareFlatten(pipeline);
        List<PipelineSelector> flatten = List.of(
                (material, type, embedded) -> GuestPipelines.instancing(PackRole.PARTICLES_FLATTEN, material, type,
                        embedded),
                (material, type, embedded) -> GuestPipelines.instancing(PackRole.PARTICLES_HANDOFF, material, type,
                        embedded));
        submitPass("flywheel:iris/particle_flatten", flattenScratch, renderModelView, group(flatten, 0));
        submitPass("flywheel:iris/particle_handoff", flattenScratch, renderModelView, group(flatten, 1));
        flattenScratch.clear();
    }

    @Override
    protected void submitPass(String label, List<InstancedDraw> list, Matrix4fc modelView,
                              PipelineSelector pipelineFor) {
        if (list.isEmpty()) {
            return;
        }
        GuestSsbos.bindForGuest((IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable());
        GuestProgram.setModelView(modelView);
        blockScratch.clear();
        blockEntityScratch.clear();
        borrowedScratch.clear();
        entityScratch.clear();
        eyesScratch.clear();
        translucentEntityScratch.clear();
        blendedEntityScratch.clear();
        particleScratch.clear();
        boolean dropBlobShadows = GuestEntityShadows.suppressed();
        for (InstancedDraw draw : list) {
            if (shadowPass && GuestPipelines.nativeGlint() && draw.material().transparency() == Transparency.GLINT)
                continue;
            if (shadowPass && (GuestDrawManager.emissive(draw.material())
                    || GuestDrawManager.depthless(draw.material(), drawTag(draw))
                    || GuestDrawManager.translucentParticle(drawTag(draw)))) continue;
            if (dropBlobShadows && GuestEntityShadows.isBlobShadow(draw.material())) {
                continue;
            }
            int routedKind = TaggedEnvironment.kind(drawTag(draw));
            if (routedKind == TaggedEnvironment.KIND_ENTITY_EYES) {
                eyesScratch.add(draw);
                continue;
            }
            if (routedKind == TaggedEnvironment.KIND_ENTITY_TRANSLUCENT) {
                translucentEntityScratch.add(draw);
                continue;
            }
            if (routedKind == TaggedEnvironment.KIND_ENTITY_BLENDED) {
                blendedEntityScratch.add(draw);
                continue;
            }
            if (routedKind == TaggedEnvironment.KIND_ENTITY_PARTICLES_TRANSLUCENT) {
                particleScratch.add(draw);
                continue;
            }
            if (routedKind == TaggedEnvironment.KIND_BORROWED_BLOCK) {
                borrowedScratch.add(draw);
                continue;
            }
            switch (TaggedEnvironment.kind(draw.groupKey.environment()
                                                        .drawTag())) {
                case TaggedEnvironment.KIND_ENTITY -> entityScratch.add(draw);
                case TaggedEnvironment.KIND_BLOCK_ENTITY -> blockEntityScratch.add(draw);
                default -> blockScratch.add(draw);
            }
        }
        submitKind(label, blockScratch, 0, modelView, pipelineFor);
        submitKind(label + "_borrowed_blocks", borrowedScratch, TaggedEnvironment.KIND_BORROWED_BLOCK, modelView,
                pipelineFor);
        if (passBlockEntities) {
            submitKind(label + "_block_entities", blockEntityScratch, TaggedEnvironment.KIND_BLOCK_ENTITY, modelView,
                    pipelineFor);
        }
        if (passEntities) {
            submitKind(label + "_entities", entityScratch, TaggedEnvironment.KIND_ENTITY, modelView, pipelineFor);
            submitKind(label + "_translucent_entities", translucentEntityScratch,
                    TaggedEnvironment.KIND_ENTITY_TRANSLUCENT, modelView, pipelineFor);
            submitKind(label + "_eyes", eyesScratch, TaggedEnvironment.KIND_ENTITY_EYES, modelView, pipelineFor);
            submitKind(label + "_blended_entities", blendedEntityScratch, TaggedEnvironment.KIND_ENTITY_BLENDED,
                    modelView, pipelineFor);
            submitKind(label + "_particles", particleScratch, TaggedEnvironment.KIND_ENTITY_PARTICLES_TRANSLUCENT,
                    modelView, pipelineFor);
        }
    }

    private void submitKind(String label, List<InstancedDraw> list, int kind, Matrix4fc modelView,
                            PipelineSelector pipelineFor) {
        if (list.isEmpty()) {
            return;
        }
        drawKind = kind;
        super.submitPass(label, list, modelView, pipelineFor);
        drawKind = 0;
    }

    private PackRole role(PackRole role) {
        return role.forKind(drawKind);
    }

    @Override
    public void renderCrumbling(List<Engine.CrumblingBlock> crumblingBlocks) {
        GuestProgram.setModelView(renderModelView);
        IrisRenderingPipeline pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        try {
            GuestSsbos.bindForGuest(pipeline);
            super.renderCrumbling(crumblingBlocks);
        } finally {
            GuestSsbos.restore(pipeline);
        }
    }

    @Override
    protected DrawTags drawTags(Environment environment, Model model, Material material, Mesh mesh) {
        return GuestDrawTags.of(environment, model, material, mesh);
    }

    @Override
    protected boolean drawnInTranslucentPass(InstancedDraw draw) {
        return GuestDrawManager.drawnInTranslucentPass(draw.material(), drawTag(draw));
    }

    @Override
    protected boolean drawnInAdditivePass(InstancedDraw draw) {
        return GuestDrawManager.drawnInAdditivePass(draw.material(), drawTag(draw));
    }

    @Override
    protected @Nullable RenderPipeline pipelineFor(Material material, InstanceType<?> type, boolean embedded) {
        return GuestPipelines.instancing(role(PackRole.SOLID), material, type, embedded);
    }

    @Override
    protected boolean keepsHistory() {
        return GuestPipelines.velocityDemanded();
    }

    @Override
    protected boolean historyPipeline(RenderPipeline pipeline) {
        return GuestPipelines.history(pipeline);
    }

    @Override
    protected @Nullable RenderPipeline crumblingPipelineFor(Material crumblingMaterial, InstanceType<?> type) {
        return GuestPipelines.crumbling(crumblingMaterial, type, false);
    }

    @Override
    protected void warmUp(Material material, InstanceType<?> type, boolean embedded) {
        if (GuestDrawManager.emissive(material)) {
            additivePipeline(material, type, embedded);
        } else if (GuestDrawManager.drawnInTranslucentPass(material, 0)) {
            translucentPipeline(material, type, embedded);
        } else {
            pipelineFor(material, type, embedded);
        }
    }

    @Override
    public void triggerFallback() {
        Minecraft.getInstance().levelExtractor.allChanged();
    }

    @Override
    public boolean isShaderPackGuest() {
        return true;
    }

    private @Nullable RenderPipeline additivePipeline(Material material, InstanceType<?> type, boolean embedded) {
        return GuestPipelines.instancing(role(PackRole.ADDITIVE), material, type, embedded);
    }

    private @Nullable RenderPipeline translucentPipeline(Material material, InstanceType<?> type,
                                                         boolean embedded) {
        return GuestPipelines.instancing(role(PackRole.TRANSLUCENT), material, type, embedded);
    }

    private @Nullable RenderPipeline shadowPipeline(Material material, InstanceType<?> type, boolean embedded) {
        return GuestPipelines.instancing(role(PackRole.SHADOW), material, type, embedded);
    }
}
