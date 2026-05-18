package dev.engine_room.vanillin.elements;

import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.component.ShadowComponent;
import net.minecraft.entity.Entity;

public final class ShadowElement extends AbstractVisual implements SimpleDynamicVisual {
    private final ShadowComponent inner;

    public ShadowElement(VisualizationContext ctx, Entity entity, float partialTick, Config config) {
        super(ctx, entity.world, partialTick);
        this.inner = new ShadowComponent(ctx, entity)
                .radius(config.radius)
                .strength(config.strength);
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        inner.beginFrame(context);
    }

    @Override
    protected void _delete() {
        inner.delete();
    }

    public record Config(float radius, float strength) {
        public static final float DEFAULT_RADIUS = 0;
        public static final float DEFAULT_STRENGTH = 1.0F;
    }
}
