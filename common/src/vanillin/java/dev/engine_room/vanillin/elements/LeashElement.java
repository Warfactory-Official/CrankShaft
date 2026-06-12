package dev.engine_room.vanillin.elements;

import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import dev.engine_room.flywheel.lib.visual.component.LeashComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import org.jspecify.annotations.Nullable;

public final class LeashElement extends AbstractVisual implements SimpleDynamicVisual {
    private final @Nullable LeashComponent component;

    public LeashElement(VisualizationContext ctx, Entity entity, float partialTick) {
        super(ctx, entity.level(), partialTick);
        this.component = entity instanceof Leashable ? new LeashComponent(ctx, entity) : null;
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        if (component != null) {
            component.beginFrame(context);
        }
    }

    @Override
    protected void _delete() {
        if (component != null) {
            component.delete();
        }
    }
}
