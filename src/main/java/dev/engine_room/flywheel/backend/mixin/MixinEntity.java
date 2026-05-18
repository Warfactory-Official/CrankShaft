package dev.engine_room.flywheel.backend.mixin;

import dev.engine_room.flywheel.impl.extension.EntityExtension;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
public abstract class MixinEntity implements EntityExtension {
}
