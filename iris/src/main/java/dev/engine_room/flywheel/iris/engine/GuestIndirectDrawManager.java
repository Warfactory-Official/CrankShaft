package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.embed.Environment;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectDraw;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectDrawManager;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainPipelines;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
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
import java.util.function.Function;

/**
 * GPU-culled indirect through the pack's block/entity programs. Two-phase HiZ draws both phases at the post-opaque seam
 * (pass 2 would otherwise land after Iris's deferred composites); the shadow pass culls against Iris's shadow
 * frustum into the pass-2 buffers before the main cull re-seeds them.
 */
public class GuestIndirectDrawManager extends IndirectDrawManager implements GuestDrawManager {
    private final GuestShadowCull shadowCull = new GuestShadowCull();
    private final List<UberDraw> plainScratch = new ArrayList<>();
    private final List<UberDraw> orderIndependentScratch = new ArrayList<>();
    private final List<UberDraw> blockScratch = new ArrayList<>();
    private final List<UberDraw> entityScratch = new ArrayList<>();
    // submitUberPass state: the kind the selectors resolve for; the kinds a pass keeps.
    private boolean entityDraws;
    private boolean passEntities = true;
    private boolean passBlockEntities = true;
    private boolean hasDraws;
    // Set for the duration of the main-pass translucent submit: the terrain stream joins the OIT producer passes.
    private @Nullable SodiumTerrainOitReplay guestTerrain;

    public GuestIndirectDrawManager(IndirectPrograms programs) {
        super(programs);
    }

    @Override
    public void prepareFrame(LightStorage lightStorage, EnvironmentStorage environmentStorage, Matrix4fc modelView,
                             Vec3i renderOrigin, boolean constantAmbientLight) {
        hasDraws = prepare(lightStorage, environmentStorage, modelView, renderOrigin, constantAmbientLight);
    }

    @Override
    public void drawOpaque(IrisRenderingPipeline pipeline) {
        if (!hasDraws) {
            TerrainDrawDispatcher.runDeferredPostVisuals();
            return;
        }
        submitMain(renderModelView);
        submitPass2IfPending();
    }

    @Override
    public boolean drawShadow(IrisRenderingPipeline pipeline, Matrix4fc shadowModelView, Vec3i renderOrigin,
                              Vec3 camera, boolean entities, boolean blockEntities) {
        if (!hasDraws) {
            return false;
        }
        GlProgram cull = shadowCull.prepare(renderOrigin, camera);
        if (cull == null) {
            return false;
        }
        GlCompat.pushDebugGroup("flywheel:iris/shadow");
        cullIntoPass2(cull);
        passEntities = entities;
        passBlockEntities = blockEntities;
        submitUberPass("flywheel:iris/shadow", uberMultiDraws, shadowModelView, true, shadowPipeline());
        passEntities = passBlockEntities = true;
        GlCompat.popDebugGroup();
        return true;
    }

    // Pass-2 buffers still hold the shadow cull: the main cull re-seeds them after the shadow pass.
    @Override
    public void drawShadowTranslucent(IrisRenderingPipeline pipeline, Matrix4fc shadowModelView, boolean entities,
                                      boolean blockEntities) {
        GlCompat.pushDebugGroup("flywheel:iris/shadow_translucent");
        passEntities = entities;
        passBlockEntities = blockEntities;
        submitTranslucent(pipeline, true, shadowModelView, true, shadowPipeline());
        passEntities = passBlockEntities = true;
        GlCompat.popDebugGroup();
    }

    @Override
    public boolean renderOit(LightStorage lightStorage, EnvironmentStorage environmentStorage,
                             @Nullable ChunkSectionsToRender chunks, @Nullable BerTranslucentCapture ber,
                             @Nullable SodiumTerrainOitReplay terrain, @Nullable FabulousCaptures fabulous) {
        // Sodium draws Iris shadow terrain through the same translucent seam.
        if (IrisApi.getInstance()
                   .isRenderingShadowPass() || (!hasDraws && terrain == null)) {
            return false;
        }
        IrisRenderingPipeline pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        try {
            // Without the contract, returning false keeps Sodium's own sorted translucent draw.
            if (terrain != null && !GuestPipelines.oitActive(pipeline, false) && !GuestPipelines.deferredOitActive(
                    pipeline)) {
                terrain = null;
            }
            // Compute cannot run inside a RenderPass; cull before opening the producer chain.
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
                        batch -> GuestPipelines.indirect(role(PackRole.TRANSLUCENT), batch.material(),
                                batch.embedded()));
                guestTerrain = null;
                submitUberPass("flywheel:iris/translucent_additive", uberOitAdditiveMultiDraws, renderModelView, false,
                        batch -> GuestPipelines.indirect(role(PackRole.TRANSLUCENT), batch.material(),
                                batch.embedded()));
                // Deferred adapters resolve later in the pack's composite phase; their capture must finish here.
                return terrain != null;
            } finally {
                GlCompat.popDebugGroup();
            }
        } finally {
            // EngineImpl catches draw failures and continues with a fallback.
            GuestSsbos.restore(pipeline);
        }
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

    private void submitTranslucent(IrisRenderingPipeline pipeline, boolean shadow, Matrix4fc modelView, boolean pass2,
                                   Function<UberDraw, RenderPipeline> plainPipeline) {
        String prefix = shadow ? "flywheel:iris/shadow_" : "flywheel:iris/";
        if (!shadow && GuestPipelines.deferredOitActive(pipeline)) {
            submitUberPass(prefix + "translucent_capture", uberOitMultiDraws, modelView, pass2, plainPipeline);
            if (guestTerrain != null) {
                guestTerrain.replayGuest(TerrainPipelines.translucentOit(3), 3,
                        Minecraft.getInstance().gameRenderer.lightmap(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            }
            return;
        }
        if (!GuestPipelines.oitActive(pipeline, shadow)) {
            submitUberPass(prefix + "translucent", uberOitMultiDraws, modelView, pass2, plainPipeline);
            return;
        }
        plainScratch.clear();
        orderIndependentScratch.clear();
        for (UberDraw batch : uberOitMultiDraws) {
            (GuestDrawManager.orderIndependent(batch.material()) ? orderIndependentScratch : plainScratch).add(batch);
        }
        submitUberPass(prefix + "translucent", plainScratch, modelView, pass2, plainPipeline);
        SodiumTerrainOitReplay terrain = shadow ? null : guestTerrain;
        if (orderIndependentScratch.isEmpty() && terrain == null) {
            return;
        }
        PackRole role = shadow ? PackRole.SHADOW : PackRole.TRANSLUCENT;
        boolean coefficients = GuestPipelines.prepareOit(pipeline, shadow);
        submitUberPass(prefix + "oit_depth_range", orderIndependentScratch, modelView, pass2,
                batch -> GuestPipelines.indirectOit(role(role), OitPass.DEPTH_RANGE, batch.material(),
                        batch.embedded()));
        replayTerrain(terrain, OitPass.DEPTH_RANGE);
        if (coefficients) {
            submitUberPass(prefix + "oit_coefficients", orderIndependentScratch, modelView, pass2,
                    batch -> GuestPipelines.indirectOit(role(role), OitPass.COEFFICIENTS, batch.material(),
                            batch.embedded()));
            replayTerrain(terrain, OitPass.COEFFICIENTS);
        }
        submitUberPass(prefix + "oit_evaluate", orderIndependentScratch, modelView, pass2,
                batch -> GuestPipelines.indirectOit(role(role), OitPass.EVALUATE, batch.material(),
                        batch.embedded()));
        replayTerrain(terrain, OitPass.EVALUATE);
        GuestOitComposite.draw(prefix + "oit_composite", shadow);
    }

    private void replayTerrain(@Nullable SodiumTerrainOitReplay terrain, OitPass pass) {
        if (terrain == null) {
            return;
        }
        terrain.replayGuest(TerrainPipelines.translucentOit(pass.ordinal()), pass.ordinal(),
                Minecraft.getInstance().gameRenderer.lightmap(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
    }

    @Override
    protected void submitUberPass(String label, List<UberDraw> batches, Matrix4fc modelViewMatrix, boolean pass2,
                                  Function<UberDraw, RenderPipeline> pipelineFor) {
        if (batches.isEmpty()) {
            return;
        }
        // Terrain can run between instance pass 1/2 and between OIT producers, using different shifted bindings.
        GuestSsbos.bindForGuest((IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable());
        GuestProgram.setModelView(modelViewMatrix);
        blockScratch.clear();
        entityScratch.clear();
        boolean dropBlobShadows = GuestEntityShadows.suppressed();
        for (UberDraw batch : batches) {
            if (dropBlobShadows && GuestEntityShadows.isBlobShadow(batch.material())) {
                continue;
            }
            (TaggedEnvironment.isEntity(batch.drawTag()) ? entityScratch : blockScratch).add(batch);
        }
        if (passBlockEntities && !blockScratch.isEmpty()) {
            super.submitUberPass(label, blockScratch, modelViewMatrix, pass2, pipelineFor);
        }
        if (passEntities && !entityScratch.isEmpty()) {
            entityDraws = true;
            super.submitUberPass(label + "_entities", entityScratch, modelViewMatrix, pass2, pipelineFor);
            entityDraws = false;
        }
    }

    private PackRole role(PackRole role) {
        return entityDraws ? role.forEntities() : role;
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

    // Guest programs compile the cutout in (Iris alpha test / contract discard) and the program per draw kind; uber
    // batches dispatch the cutout at runtime.
    @Override
    protected boolean incompatibleUber(IndirectDraw a, IndirectDraw b) {
        return super.incompatibleUber(a, b) || a.material()
                                                .cutout() != b.material()
                                                              .cutout()
                || TaggedEnvironment.isEntity(a.drawTag()) != TaggedEnvironment.isEntity(b.drawTag());
    }

    @Override
    protected boolean bindlessTextures() {
        return false;
    }

    @Override
    protected RenderPipeline uberPipelineFor(Material material, boolean embedded) {
        return GuestPipelines.indirect(role(PackRole.SOLID), material, embedded);
    }

    @Override
    protected RenderPipeline crumblingPipelineFor(Material crumblingMaterial, InstanceType<?> type) {
        return GuestPipelines.crumbling(crumblingMaterial, type, true);
    }

    @Override
    public boolean isShaderPackGuest() {
        return true;
    }

    @Override
    public void delete() {
        shadowCull.delete();
        super.delete();
    }

    private Function<UberDraw, RenderPipeline> shadowPipeline() {
        return batch -> GuestPipelines.indirect(role(PackRole.SHADOW), batch.material(), batch.embedded());
    }
}
