package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.backend.Camera;
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
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.engine.embed.Environment;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

import java.util.List;

public class EngineImpl implements Engine {
    private final DrawManager<? extends AbstractInstancer<?>> drawManager;
    private final int sqrMaxOriginDistance;
    private final EnvironmentStorage environmentStorage;
    private final LightStorage lightStorage;

    private BlockPos renderOrigin = BlockPos.ORIGIN;

    public EngineImpl(World level, DrawManager<? extends AbstractInstancer<?>> drawManager, int maxOriginDistance) {
        this.drawManager = drawManager;
        sqrMaxOriginDistance = maxOriginDistance * maxOriginDistance;
        environmentStorage = new EnvironmentStorage();
        lightStorage = new LightStorage(level);
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
        // Snap by eye position — renderOrigin should track the player, not the third-person
        // viewpoint that swings 4 blocks behind. Otherwise an F5 toggle would trigger a needless
        // origin snap + full visual rebuild.
        Vec3d eyePos = camera.eyePosition();
        double dx = renderOrigin.getX() - eyePos.x;
        double dy = renderOrigin.getY() - eyePos.y;
        double dz = renderOrigin.getZ() - eyePos.z;
        double distanceSqr = dx * dx + dy * dy + dz * dz;

        if (distanceSqr <= sqrMaxOriginDistance) {
            return false;
        }

        BlockPos oldOrigin = renderOrigin;
        renderOrigin = new BlockPos(eyePos);
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
    public void onLightUpdate(long sectionPos, EnumSkyBlock layer) {
        lightStorage.onLightUpdate(sectionPos);
    }

    @Override
    public void render(RenderContext context) {
        try (var state = GlStateTracker.getRestoreState()) {
            // Observe BackendDebugFlags.LIGHT_STORAGE_VIEW transitions on the render thread so
            // that the SPSC effects transaction queue keeps its single-producer invariant.
            lightStorage.tickDebugVisualization();
            Uniforms.update(context);
            environmentStorage.flush();
            drawManager.render(lightStorage, environmentStorage);
        } catch (Exception e) {
            FlwBackend.LOGGER.error("Falling back", e);
            triggerFallback();
        }
    }

    @Override
    public void renderCrumbling(RenderContext context, List<CrumblingBlock> crumblingBlocks) {
        try (var state = GlStateTracker.getRestoreState()) {
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

    public <I extends Instance> Instancer<I> instancer(Environment environment, InstanceType<I> type, Model model, int bias) {
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
