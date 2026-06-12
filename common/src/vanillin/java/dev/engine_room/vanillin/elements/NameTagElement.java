package dev.engine_room.vanillin.elements;

import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.component.NameTagComponent;
import net.minecraft.world.entity.Entity;

public final class NameTagElement extends AbstractVisual implements SimpleDynamicVisual {
    private final NameTagComponent component;

    public NameTagElement(VisualizationContext ctx, Entity entity, float partialTick) {
        super(ctx, entity.level(), partialTick);
        this.component = new NameTagComponent(ctx, entity);
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        component.beginFrame(context);
    }

    @Override
    protected void _delete() {
        component.delete();
    }
}
