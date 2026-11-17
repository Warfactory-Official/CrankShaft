package dev.engine_room.flywheel.iris.engine;

import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.compile.RenderPipelineCompiler;
import dev.engine_room.flywheel.backend.compile.ShaderWarmup;
import dev.engine_room.flywheel.backend.Backends;
import dev.engine_room.flywheel.backend.BackendRecovery;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.GpuTimer;
import dev.engine_room.flywheel.backend.engine.AbstractInstancer;
import dev.engine_room.flywheel.backend.engine.DrawManager;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.iris.compile.ContractProperties;
import dev.engine_room.flywheel.iris.compile.GuestPipelines;
import dev.engine_room.flywheel.iris.compile.GuestProgram;
import dev.engine_room.flywheel.iris.compile.GuestSsbos;
#if MC < 26.3
import dev.engine_room.flywheel.iris.compile.patches.GuestLodOcclusion;
#endif
import dev.engine_room.flywheel.iris.compile.FarStaticShadows;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * Uploads before first main/shadow pass each frame.
 */
public class GuestEngine extends EngineImpl {
    private static final NamespacedId CONVERTING_VILLAGER = new NamespacedId("minecraft",
            "zombie_villager_converting");

    private final GuestDrawManager guest;
    private final MeshPool meshPool;
    private final GuestVertexExtras vertexExtras = new GuestVertexExtras();
    private final boolean constantAmbientLight;
    private final Matrix4f lastModelView = new Matrix4f();
    private @Nullable Vec3 lastCamera;
    private @Nullable Matrix4f translucentShadowModelView;
    private boolean translucentShadowEntities;
    private boolean translucentShadowBlockEntities;
    private boolean preparedForMainPass;

    public <M extends DrawManager<? extends AbstractInstancer<?>> & GuestDrawManager> GuestEngine(LevelAccessor level,
                                                                                                  M guest) {
        super(level, guest, Backends.MAX_ORIGIN_DISTANCE);
        this.guest = guest;
        meshPool = guest.meshPool();
        meshPool.extras(vertexExtras);
        constantAmbientLight = level.dimensionType()
                                    .cardinalLightType() == CardinalLighting.Type.NETHER;
        GuestShadows.register();
    }

    private static IrisRenderingPipeline pipeline() {
        if (!(Iris.getPipelineManager()
                  .getPipelineNullable() instanceof IrisRenderingPipeline pipeline)) {
            throw new IllegalStateException("Guest engine rendering without an active shaderpack");
        }
        return pipeline;
    }

    @Override
    public int drawTag(BlockEntity blockEntity) {
        Object2IntMap<BlockState> ids = WorldRenderingSettings.INSTANCE.getBlockStateIds();
        return ids == null ? 0 : TaggedEnvironment.tag(TaggedEnvironment.KIND_BLOCK_ENTITY,
                ids.applyAsInt(blockEntity.getBlockState()));
    }

    @Override
    public int drawTag(Entity entity) {
        Object2IntFunction<NamespacedId> ids = WorldRenderingSettings.INSTANCE.getEntityIds();
        if (ids == null) {
            return 0;
        }
        if (entity instanceof ZombieVillager villager && villager.isConverting()
                && WorldRenderingSettings.INSTANCE.hasVillagerConversionId()) {
            return TaggedEnvironment.tag(TaggedEnvironment.KIND_ENTITY, ids.applyAsInt(CONVERTING_VILLAGER));
        }
        Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return TaggedEnvironment.tag(TaggedEnvironment.KIND_ENTITY,
                ids.applyAsInt(new NamespacedId(type.getNamespace(), type.getPath())));
    }

    @Override
    public void render(RenderContext context) {
        // As EngineImpl: no draw while ShaderWarmup.pending.
        if (ShaderWarmup.pending()) return;
        IrisRenderingPipeline pipeline = pipeline();
        try {
            if (!preparedForMainPass) {
                prepare(context);
            }
            preparedForMainPass = false;
            #if MC < 26.3
            GuestLodOcclusion.captureTerrainDepth(pipeline);
            #endif
            guest.drawOpaque(pipeline);
        } catch (BackendUnavailableException e) {
            BackendRecovery.reject(BackendManager.currentBackend(), e);
            ShaderWarmup.fallingBack(guest::triggerFallback);
        } finally {
            GuestSsbos.restore(pipeline);
        }
    }

    void renderShadow(RenderContext context, Matrix4fc shadowModelView, Vec3 camera, IrisRenderingPipeline pipeline) {
        if (ShaderWarmup.pending()) return;
        try {
            prepare(context);
            preparedForMainPass = true;
            PackShadowDirectives directives = ((IrisRenderingPipelineAccessor) pipeline).flywheel$shadowDirectives();
            ContractProperties contract = GuestPipelines.contractProperties(pipeline);
            if (contract == null || contract.shadowEnabled()) {
                boolean entities = directives.shouldRenderEntities();
                boolean blockEntities = directives.shouldRenderBlockEntities();
                Matrix4f modelView = originRelative(shadowModelView, camera);
                if (guest.drawShadow(pipeline, modelView, renderOrigin(), camera, entities, blockEntities)
                        && directives.shouldRenderTranslucent()) {
                    translucentShadowModelView = modelView;
                    translucentShadowEntities = entities;
                    translucentShadowBlockEntities = blockEntities;
                }
                if (blockEntities) {
                    FarStaticShadows.render(pipeline, modelView, renderOrigin(), camera, constantAmbientLight);
                }
            }
        } catch (BackendUnavailableException e) {
            BackendRecovery.reject(BackendManager.currentBackend(), e);
            ShaderWarmup.fallingBack(guest::triggerFallback);
        } finally {
            GuestSsbos.restore(pipeline);
        }
    }

    void renderShadowTranslucent(IrisRenderingPipeline pipeline) {
        Matrix4f modelView = translucentShadowModelView;
        if (modelView == null) {
            return;
        }
        translucentShadowModelView = null;
        try {
            guest.drawShadowTranslucent(pipeline, modelView, translucentShadowEntities,
                    translucentShadowBlockEntities);
        } catch (BackendUnavailableException e) {
            BackendRecovery.reject(BackendManager.currentBackend(), e);
            ShaderWarmup.fallingBack(guest::triggerFallback);
        } finally {
            GuestSsbos.restore(pipeline);
        }
    }

    private void prepare(RenderContext context) {
        RenderPipelineCompiler.poll();
        GuestPipelines.poll();
        if (vertexExtras.blockIdsChanged()) {
            meshPool.invalidateExtras();
        }
        Uniforms.update(context);
        GpuTimer.beginFrame();
        environmentStorage().flush();
        Vec3 camera = context.camera()
                             .position();
        if (lastCamera == null) {
            lastModelView.set(context.modelView());
            lastCamera = camera;
        }
        GuestProgram.setPreviousModelView(originRelative(lastModelView, lastCamera));
        lastModelView.set(context.modelView());
        lastCamera = camera;
        guest.prepareFrame(lightStorage(), environmentStorage(), originRelative(context.modelView(), camera),
                renderOrigin(), constantAmbientLight);
    }

    private Matrix4f originRelative(Matrix4fc modelView, Vec3 camera) {
        Vec3i origin = renderOrigin();
        return new Matrix4f(modelView).translate((float) (origin.getX() - camera.x),
                (float) (origin.getY() - camera.y), (float) (origin.getZ() - camera.z));
    }

    @Override
    public void delete() {
        FarStaticShadows.releaseSource();
        super.delete();
    }
}
