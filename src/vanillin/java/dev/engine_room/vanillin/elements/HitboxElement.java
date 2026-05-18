package dev.engine_room.vanillin.elements;

import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.component.HitboxComponent;
import net.minecraft.entity.Entity;

public final class HitboxElement extends AbstractVisual implements SimpleDynamicVisual {
    private final HitboxComponent inner;

    public HitboxElement(VisualizationContext ctx, Entity entity, float partialTick) {
        super(ctx, entity.world, partialTick);
        this.inner = new HitboxComponent(ctx, entity);
    }

    public HitboxElement(VisualizationContext ctx, Entity entity, float partialTick, Boolean showEyeBox) {
        super(ctx, entity.world, partialTick);
        this.inner = new HitboxComponent(ctx, entity).showEyeBox(showEyeBox);
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        inner.beginFrame(context);
    }

    @Override
    protected void _delete() {
        inner.delete();
    }
}
