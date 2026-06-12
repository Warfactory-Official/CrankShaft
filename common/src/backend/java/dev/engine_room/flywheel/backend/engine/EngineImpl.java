package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.instance.InstancerProvider;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.GpuTimer;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.engine.embed.Environment;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class EngineImpl implements Engine {
    private final DrawManager<? extends AbstractInstancer<?>> drawManager;
    private final int sqrMaxOriginDistance;
    private final EnvironmentStorage environmentStorage;
    private final LightStorage lightStorage;
    private final boolean constantAmbientLight;

    private BlockPos renderOrigin = BlockPos.ZERO;

    public EngineImpl(LevelAccessor level, DrawManager<? extends AbstractInstancer<?>> drawManager,
                      int maxOriginDistance) {
        this.drawManager = drawManager;
        sqrMaxOriginDistance = maxOriginDistance * maxOriginDistance;
        environmentStorage = new EnvironmentStorage();
        lightStorage = new LightStorage(level);
        // 26.2 replaced DimensionSpecialEffects.constantAmbientLight() with the per-dimension CardinalLighting
        // type; NETHER is the old "constant ambient" (non-directional sky) case -> diffuseNether in the shader.
        constantAmbientLight = level.dimensionType()
                                    .cardinalLightType() == CardinalLighting.Type.NETHER;
    }

    @Override
    public VisualizationContext createVisualizationContext() {
        return new VisualizationContextImpl();
    }

    @Override
    public Plan<RenderContext> createFramePlan() {
        return drawManager.createFramePlan()
                          .and(lightStorage.createFramePlan());
    }

    @Override
    public Vec3i renderOrigin() {
        return renderOrigin;
    }

    @Override
    public boolean updateRenderOrigin(Camera camera) {
        Vec3 cameraPos = camera.position();
        double dx = renderOrigin.getX() - cameraPos.x;
        double dy = renderOrigin.getY() - cameraPos.y;
        double dz = renderOrigin.getZ() - cameraPos.z;
        double distanceSqr = dx * dx + dy * dy + dz * dz;

        if (distanceSqr <= sqrMaxOriginDistance) {
            return false;
        }

        BlockPos oldOrigin = renderOrigin;
        BlockPos newOrigin = BlockPos.containing(cameraPos);
        FlwBackend.LOGGER.info("Attempting render origin change: {} -> {} (camera drift {} blocks)",
                oldOrigin, newOrigin, (int) Math.sqrt(distanceSqr));
        renderOrigin = newOrigin;
        drawManager.onRenderOriginChanged();
        FlwBackend.LOGGER.debug("Render origin snap: {} -> {} (camera drift {} blocks); recreating all visuals",
                oldOrigin, renderOrigin, Integer.toString((int) Math.sqrt(distanceSqr)));
        return true;
    }

    @Override
    public void lightSections(LongSet sections) {
        lightStorage.sections(sections);
    }

    @Override
    public void onLightUpdate(long sectionPos, LightLayer layer) {
        lightStorage.onLightUpdate(sectionPos);
    }

    @Override
    public void render(RenderContext context) {
        // 26.2: no GlStateTracker save/restore -- Mojang RHI leaves GL caches consistent; raw restore() would desync them.
        try {
            Uniforms.update(context);
            // Rotate the GL GPU-timer's per-frame query ring here, before the frame's labeled visual GL work
            // (no-op on a Vulkan host, which self-rotates on its submit index).
            GpuTimer.beginFrame();
            environmentStorage.flush();
            drawManager.render(lightStorage, environmentStorage, renderOriginModelView(context), renderOrigin,
                    constantAmbientLight);
        } catch (Exception e) {
            FlwBackend.LOGGER.error("Falling back", e);
            triggerFallback();
        }
    }

    private Matrix4f renderOriginModelView(RenderContext context) {
        Vec3 cameraPos = context.camera()
                                .position();
        return new Matrix4f(context.modelView()).translate(
                (float) (renderOrigin.getX() - cameraPos.x),
                (float) (renderOrigin.getY() - cameraPos.y),
                (float) (renderOrigin.getZ() - cameraPos.z));
    }

    @Override
    public void renderOit(RenderContext context) {
        renderOit(context, null, null, null, null);
    }

    public boolean renderOit(RenderContext context, @Nullable ChunkSectionsToRender chunks,
                             @Nullable BerTranslucentCapture ber, @Nullable SodiumTerrainOitReplay terrain,
                             @Nullable FabulousCaptures fabulous) {
        try {
            return drawManager.renderOit(lightStorage, environmentStorage, chunks, ber, terrain, fabulous);
        } catch (Exception e) {
            FlwBackend.LOGGER.error("Falling back", e);
            triggerFallback();
            return false;
        }
    }

    @Override
    public void renderCrumbling(RenderContext context, List<CrumblingBlock> crumblingBlocks) {
        // No GlStateTracker save/restore -- see renderOit(). (the crumbling raw-GL body must reconcile
        // the encoder rather than raw-restore.)
        try {
            drawManager.renderCrumbling(crumblingBlocks);
        } catch (Exception e) {
            FlwBackend.LOGGER.error("Falling back", e);
            triggerFallback();
        }
    }

    @Override
    public void delete() {
        drawManager.delete();
        lightStorage.delete();
        environmentStorage.delete();
    }

    private void triggerFallback() {
        drawManager.triggerFallback();
    }

    public <I extends Instance> Instancer<I> instancer(Environment environment, InstanceType<I> type, Model model,
                                                       int bias) {
        return drawManager.getInstancer(environment, type, model, bias);
    }

    public EnvironmentStorage environmentStorage() {
        return environmentStorage;
    }

    public LightStorage lightStorage() {
        return lightStorage;
    }

    public DrawManager<? extends AbstractInstancer<?>> drawManager() {
        return drawManager;
    }

    private class VisualizationContextImpl implements VisualizationContext {
        private final InstancerProviderImpl instancerProvider;

        public VisualizationContextImpl() {
            instancerProvider = new InstancerProviderImpl(EngineImpl.this);
        }

        @Override
        public InstancerProvider instancerProvider() {
            return instancerProvider;
        }

        @Override
        public Vec3i renderOrigin() {
            return EngineImpl.this.renderOrigin();
        }

        @Override
        public VisualEmbedding createEmbedding(Vec3i renderOrigin) {
            var out = new EmbeddedEnvironment(EngineImpl.this, renderOrigin);
            environmentStorage.track(out);
            return out;
        }
    }
}
