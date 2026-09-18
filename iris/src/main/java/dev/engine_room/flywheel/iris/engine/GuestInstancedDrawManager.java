package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.pipeline.RenderPipeline;
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
    private final List<InstancedDraw> blockScratch = new ArrayList<>();
    private final List<InstancedDraw> entityScratch = new ArrayList<>();
    // submitPass state: the kind the selectors resolve for; the kinds a pass keeps.
    private boolean entityDraws;
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
            if (!oitAdditiveDraws.isEmpty()) {
                submitPass("flywheel:iris/translucent_additive", oitAdditiveDraws, renderModelView,
                        this::translucentPipeline);
            }
            return false;
        } finally {
            GlCompat.popDebugGroup();
            GuestSsbos.restore(pipeline);
        }
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
            (GuestDrawManager.orderIndependent(draw.material()) ? orderIndependentScratch : plainScratch).add(draw);
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
        entityScratch.clear();
        boolean dropBlobShadows = GuestEntityShadows.suppressed();
        for (InstancedDraw draw : list) {
            if (dropBlobShadows && GuestEntityShadows.isBlobShadow(draw.material())) {
                continue;
            }
            (TaggedEnvironment.isEntity(draw.groupKey.environment()
                                                     .drawTag()) ? entityScratch : blockScratch).add(draw);
        }
        if (passBlockEntities && !blockScratch.isEmpty()) {
            super.submitPass(label, blockScratch, modelView, pipelineFor);
        }
        if (passEntities && !entityScratch.isEmpty()) {
            entityDraws = true;
            super.submitPass(label + "_entities", entityScratch, modelView, pipelineFor);
            entityDraws = false;
        }
    }

    private PackRole role(PackRole role) {
        return entityDraws ? role.forEntities() : role;
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
    protected boolean drawnInTranslucentPass(Material material) {
        return material.transparency() == Transparency.TRANSLUCENT
                || material.transparency() == Transparency.TRANSLUCENT_ALPHA_REPLACE
                || OitTransparency.orderIndependent(material);
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
        if (OitTransparency.additive(material) || drawnInTranslucentPass(material)) {
            translucentPipeline(material, type, false);
        } else {
            pipelineFor(material, type, false);
        }
    }

    @Override
    public boolean isShaderPackGuest() {
        return true;
    }

    private RenderPipeline translucentPipeline(Material material, InstanceType<?> type, boolean embedded) {
        return GuestPipelines.instancing(role(PackRole.TRANSLUCENT), material, type, embedded);
    }

    private RenderPipeline shadowPipeline(Material material, InstanceType<?> type, boolean embedded) {
        return GuestPipelines.instancing(role(PackRole.SHADOW), material, type, embedded);
    }
}
