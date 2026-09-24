package dev.engine_room.flywheel.impl.mixin.sodium;

import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Option.class)
public interface SodiumOptionAccessor {
    @Accessor("id")
    Identifier flywheel$id();
}
