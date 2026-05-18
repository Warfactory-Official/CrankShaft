package dev.engine_room.vanillin.elements;

import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.component.FireComponent;
import net.minecraft.entity.Entity;

public final class FireElement extends AbstractVisual implements SimpleDynamicVisual {
    private final FireComponent inner;

    public FireElement(VisualizationContext ctx, Entity entity, float partialTick) {
        super(ctx, entity.world, partialTick);
        this.inner = new FireComponent(ctx, entity);
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
