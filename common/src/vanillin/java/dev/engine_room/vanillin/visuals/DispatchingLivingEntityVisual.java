package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.EntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.world.entity.LivingEntity;

import java.util.function.Function;

public class DispatchingLivingEntityVisual<T extends LivingEntity> implements EntityVisual<T>, SimpleDynamicVisual {
    private final VisualizationContext ctx;
    private final T entity;
    private final Function<T, LivingEntityVisual.Config> dispatch;
    private LivingEntityVisual.Config config;
    private LivingEntityVisual<T> inner;

    public DispatchingLivingEntityVisual(VisualizationContext ctx, T entity, float partialTick,
                                         Function<T, LivingEntityVisual.Config> dispatch) {
        this.ctx = ctx;
        this.entity = entity;
        this.dispatch = dispatch;
        this.config = dispatch.apply(entity);
        this.inner = new LivingEntityVisual<>(ctx, entity, partialTick, config);
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        LivingEntityVisual.Config resolved = dispatch.apply(entity);
        if (resolved != config) {
            inner.delete();
            config = resolved;
            inner = new LivingEntityVisual<>(ctx, entity, context.partialTick(), resolved);
        }
        inner.beginFrame(context);
    }

    @Override
    public void update(float partialTick) {
        inner.update(partialTick);
    }

    @Override
    public void delete() {
        inner.delete();
    }
}
