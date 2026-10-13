package dev.engine_room.flywheel.iris.engine;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.Backends;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Uploads once before the first main or shadow draw.
 */
public final class ExperimentalEngine extends EngineImpl {
    private static boolean registered;
    private final ExperimentalDrawManager guest;
    private final boolean ambient;
    private boolean prepared;
    private Matrix4f translucentShadowView;
    private boolean shadowEntities;
    private boolean shadowBlockEntities;

    public ExperimentalEngine(LevelAccessor level, boolean indirect) {
        this(level, new ExperimentalDrawManager(indirect));
    }

    private ExperimentalEngine(LevelAccessor level, ExperimentalDrawManager guest) {
        super(level, guest, Backends.MAX_ORIGIN_DISTANCE);
        this.guest = guest;
        ambient = level.dimensionType().cardinalLightType() == CardinalLighting.Type.NETHER;
        if (!registered) {
            registered = true;
            IrisApi.getInstance().registerShadowRenderCallback((view, _, x, y, z, _) -> {
                VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
                if (manager == null || !(manager.getEngineImpl() instanceof ExperimentalEngine)) return;
                RenderContext context = manager.currentFrameContext();
                if (context != null && manager.syncFrameEngine() instanceof ExperimentalEngine engine) {
                    engine.shadow(context, view, new Vec3(x, y, z));
                }
            });
        }
    }

    /**
     * Render-thread shadow callback after the opaque-depth snapshot, keeping guest translucents out of that
     * snapshot.
     */
    public static void afterPreTranslucentDepth() {
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        if (manager != null && manager.getEngineImpl() instanceof ExperimentalEngine engine
                && engine.translucentShadowView != null) {
            engine.guest.draw(engine.translucentShadowView, true, true, engine.shadowEntities,
                    engine.shadowBlockEntities);
            engine.translucentShadowView = null;
        }
    }

    @Override
    public int drawTag(BlockEntity blockEntity) {
        var ids = WorldRenderingSettings.INSTANCE.getBlockStateIds();
        return ids == null ? 0 : TaggedEnvironment.tag(TaggedEnvironment.KIND_BLOCK_ENTITY,
                ids.applyAsInt(blockEntity.getBlockState()));
    }

    @Override
    public int drawTag(Entity entity) {
        var ids = WorldRenderingSettings.INSTANCE.getEntityIds();
        if (ids == null) return 0;
        NamespacedId id;
        if (entity instanceof ZombieVillager villager && villager.isConverting()
                && WorldRenderingSettings.INSTANCE.hasVillagerConversionId()) {
            id = new NamespacedId("minecraft", "zombie_villager_converting");
        } else {
            var type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            id = new NamespacedId(type.getNamespace(), type.getPath());
        }
        return TaggedEnvironment.tag(TaggedEnvironment.KIND_ENTITY, ids.applyAsInt(id));
    }

    @Override
    public void render(RenderContext context) {
        if (!prepared) prepare(context);
        prepared = false;
        guest.draw(originRelative(context.modelView(), context.camera().position()), false, false, true, true);
    }

    private void shadow(RenderContext context, Matrix4fc view, Vec3 camera) {
        prepare(context);
        prepared = true;
        var pipeline = (IrisRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        var access = pipeline;
        if (!((IrisRenderingPipelineAccessor) access).flywheel$pack()
                                                     .flywheel$contractProperties().shadowEnabled()) {
            translucentShadowView = null;
            return;
        }
        var directives = ((IrisRenderingPipelineAccessor) access).flywheel$shadowDirectives();
        shadowEntities = directives.shouldRenderEntities();
        shadowBlockEntities = directives.shouldRenderBlockEntities();
        Matrix4f modelView = originRelative(view, camera);
        guest.draw(modelView, true, false, shadowEntities, shadowBlockEntities);
        translucentShadowView = directives.shouldRenderTranslucent() ? modelView : null;
    }

    private void prepare(RenderContext context) {
        Uniforms.update(context);
        environmentStorage().flush();
        guest.prepare(lightStorage(), environmentStorage(),
                originRelative(context.modelView(), context.camera().position()),
                renderOrigin(), ambient);
    }

    private Matrix4f originRelative(Matrix4fc view, Vec3 camera) {
        var origin = renderOrigin();
        return new Matrix4f(view).translate((float) (origin.getX() - camera.x), (float) (origin.getY() - camera.y),
                (float) (origin.getZ() - camera.z));
    }
}
