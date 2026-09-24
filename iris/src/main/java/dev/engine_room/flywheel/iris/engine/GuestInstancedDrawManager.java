package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
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
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Instancing through the pack's block/entity programs: solid at the post-opaque seam, translucent at the translucent
 * seam.
 */
public class GuestInstancedDrawManager extends InstancedDrawManager implements GuestDrawManager {
    private final List<InstancedDraw> plainScratch = new ArrayList<>();
    private final List<InstancedDraw> orderIndependentScratch = new ArrayList<>();
    private final List<InstancedDraw> depthFillScratch = new ArrayList<>();
    private final List<InstancedDraw> blockScratch = new ArrayList<>();
    private final List<InstancedDraw> blockEntityScratch = new ArrayList<>();
    private final List<InstancedDraw> entityScratch = new ArrayList<>();
    private final List<InstancedDraw> eyesScratch = new ArrayList<>();
    private final List<InstancedDraw> translucentEntityScratch = new ArrayList<>();
    private final List<InstancedDraw> blendedEntityScratch = new ArrayList<>();
    // submitPass state: the kind the selectors resolve for; the kinds a pass keeps.
    private int drawKind;
    private boolean passEntities = true;
    private boolean passBlockEntities = true;

    public GuestInstancedDrawManager(InstancingPrograms programs) {
        super(programs);
    }

    @Override
    public void prepareFrame(LightStorage lightStorage, EnvironmentStorage environmentStorage, Matrix4fc modelView,
                             Vec3i renderOrigin, boolean constantAmbientLight) {
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
    public boolean drawShadow(IrisRenderingPipeline pipeline, Matrix4fc shadowModelView, Vec3i renderOrigin,
                              Vec3 camera, boolean entities, boolean blockEntities) {
        GlCompat.pushDebugGroup("flywheel:iris/shadow");
        passEntities = entities;
        passBlockEntities = blockEntities;
        if (!draws.isEmpty()) {
            submitPass("flywheel:iris/shadow", draws, shadowModelView, this::shadowPipeline);
        }
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
        submitTranslucent(pipeline, true, shadowModelView, this::shadowPipeline);
        passEntities = passBlockEntities = true;
        GlCompat.popDebugGroup();
    }

    @Override
    public boolean renderOit(LightStorage lightStorage, EnvironmentStorage environmentStorage,
                             @Nullable ChunkSectionsToRender chunks, @Nullable BerTranslucentCapture ber,
                             @Nullable SodiumTerrainOitReplay terrain, @Nullable FabulousCaptures fabulous) {
        // Sodium draws Iris shadow terrain through the same translucent seam.
        if (IrisApi.getInstance()
                   .isRenderingShadowPass()) {
            return false;
        }
        IrisRenderingPipeline pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        GlCompat.pushDebugGroup("flywheel:iris/translucent");
        try {
            submitTranslucent(pipeline, false, renderModelView, this::translucentPipeline);
            if (!GuestPipelines.deferredEmissive(pipeline)) {
                submitPass("flywheel:iris/translucent_additive", oitAdditiveDraws, renderModelView,
                        this::additivePipeline);
            }
            submitDepthFill(pipeline);
            return false;
        } finally {
            GlCompat.popDebugGroup();
            GuestSsbos.restore(pipeline);
        }
    }

    // Pack composites resolve sky, clouds, fog and translucency from depth: a colour-only surface reads as whatever
    // lies behind it. A separate pass, so additive stacks and translucent layers still blend.
    private void submitDepthFill(IrisRenderingPipeline pipeline) {
        depthFillScratch.clear();
        if (!GuestPipelines.deferredTranslucent(pipeline)) {
            boolean oit = GuestPipelines.oitActive(pipeline, false);
            for (InstancedDraw draw : oitDraws) {
                if (!draw.material()
                         .writeMask()
                         .depth() && !(oit && GuestDrawManager.orderIndependent(draw.material(), drawTag(draw)))) {
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
        submitPass("flywheel:iris/depth_fill", depthFillScratch, renderModelView,
                (material, type, embedded) -> GuestPipelines.instancingDepthFill(
                        role(GuestDrawManager.emissive(material) ? PackRole.ADDITIVE : PackRole.TRANSLUCENT), material,
                        type, embedded));
    }

    private void submitTranslucent(IrisRenderingPipeline pipeline, boolean shadow, Matrix4fc modelView,
                                   PipelineSelector plainPipeline) {
        String prefix = shadow ? "flywheel:iris/shadow_" : "flywheel:iris/";
        if (oitDraws.isEmpty()) {
            return;
        }
        if (!GuestPipelines.oitActive(pipeline, shadow)) {
            submitPass(prefix + "translucent", oitDraws, modelView, plainPipeline);
            return;
        }
        plainScratch.clear();
        orderIndependentScratch.clear();
        for (InstancedDraw draw : oitDraws) {
            (GuestDrawManager.orderIndependent(draw.material(), drawTag(draw)) ? orderIndependentScratch
                    : plainScratch).add(draw);
        }
        if (!plainScratch.isEmpty()) {
            submitPass(prefix + "translucent", plainScratch, modelView, plainPipeline);
        }
        if (orderIndependentScratch.isEmpty()) {
            return;
        }
        PackRole role = shadow ? PackRole.SHADOW : PackRole.TRANSLUCENT;
        boolean coefficients = GuestPipelines.prepareOit(pipeline, shadow);
        submitPass(prefix + "oit_depth_range", orderIndependentScratch, modelView,
                (material, type, embedded) -> GuestPipelines.instancingOit(role(role), OitPass.DEPTH_RANGE, material,
                        type, embedded));
        if (coefficients) {
            submitPass(prefix + "oit_coefficients", orderIndependentScratch, modelView,
                    (material, type, embedded) -> GuestPipelines.instancingOit(role(role), OitPass.COEFFICIENTS,
                            material, type, embedded));
        }
        submitPass(prefix + "oit_evaluate", orderIndependentScratch, modelView,
                (material, type, embedded) -> GuestPipelines.instancingOit(role(role), OitPass.EVALUATE, material,
                        type, embedded));
        GuestOitComposite.draw(prefix + "oit_composite", shadow);
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
        entityScratch.clear();
        eyesScratch.clear();
        translucentEntityScratch.clear();
        blendedEntityScratch.clear();
        boolean dropBlobShadows = GuestEntityShadows.suppressed();
        for (InstancedDraw draw : list) {
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
            switch (TaggedEnvironment.kind(draw.groupKey.environment()
                                                         .drawTag())) {
                case TaggedEnvironment.KIND_ENTITY -> entityScratch.add(draw);
                case TaggedEnvironment.KIND_BLOCK_ENTITY -> blockEntityScratch.add(draw);
                default -> blockScratch.add(draw);
            }
        }
        submitKind(label, blockScratch, 0, modelView, pipelineFor);
        if (passBlockEntities) {
            submitKind(label + "_block_entities", blockEntityScratch, TaggedEnvironment.KIND_BLOCK_ENTITY, modelView,
                    pipelineFor);
        }
        if (passEntities) {
            submitKind(label + "_entities", entityScratch, TaggedEnvironment.KIND_ENTITY, modelView, pipelineFor);
            // Vanilla submits a body before its layers.
            submitKind(label + "_translucent_entities", translucentEntityScratch,
                    TaggedEnvironment.KIND_ENTITY_TRANSLUCENT, modelView, pipelineFor);
            submitKind(label + "_eyes", eyesScratch, TaggedEnvironment.KIND_ENTITY_EYES, modelView, pipelineFor);
            submitKind(label + "_blended_entities", blendedEntityScratch, TaggedEnvironment.KIND_ENTITY_BLENDED,
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

    private static int drawTag(InstancedDraw draw) {
        DrawTags tags = draw.tags();
        return tags == null ? 0 : tags.drawTag();
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
    protected RenderPipeline pipelineFor(Material material, InstanceType<?> type, boolean embedded) {
        return GuestPipelines.instancing(role(PackRole.SOLID), material, type, embedded);
    }

    @Override
    protected RenderPipeline crumblingPipelineFor(Material crumblingMaterial, InstanceType<?> type) {
        return GuestPipelines.crumbling(crumblingMaterial, type, false);
    }

    @Override
    protected void warmUp(Material material, InstanceType<?> type) {
        if (GuestDrawManager.emissive(material)) {
            additivePipeline(material, type, false);
        } else if (GuestDrawManager.drawnInTranslucentPass(material, 0)) {
            translucentPipeline(material, type, false);
        } else {
            pipelineFor(material, type, false);
        }
    }

    @Override
    public boolean isShaderPackGuest() {
        return true;
    }

    private RenderPipeline additivePipeline(Material material, InstanceType<?> type, boolean embedded) {
        return GuestPipelines.instancing(role(PackRole.ADDITIVE), material, type, embedded);
    }

    private RenderPipeline translucentPipeline(Material material, InstanceType<?> type, boolean embedded) {
        return GuestPipelines.instancing(role(PackRole.TRANSLUCENT), material, type, embedded);
    }

    private RenderPipeline shadowPipeline(Material material, InstanceType<?> type, boolean embedded) {
        return GuestPipelines.instancing(role(PackRole.SHADOW), material, type, embedded);
    }
}
