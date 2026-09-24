package dev.engine_room.flywheel.impl.mixin;

import net.mehvahdjukaar.polytone.content.entity.EntityModifier;
import net.mehvahdjukaar.polytone.content.entity.EntityModifiersManager;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(EntityModifiersManager.class)
public interface PolytoneEntityModifiersAccessor {
    @Accessor("emittersPerEntity")
    Map<EntityType<?>, EntityModifier> flywheel$emitters();
}
