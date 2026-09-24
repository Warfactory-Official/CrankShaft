package dev.engine_room.flywheel.impl.mixin;

import net.mehvahdjukaar.polytone.common.expressions.impl.IEntityExp;
import net.mehvahdjukaar.polytone.content.color.ColorManager;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ColorManager.class)
public interface PolytoneColorManagerAccessor {
    @Accessor("xpOrbColor")
    @Nullable IEntityExp flywheel$xpOrbColor();

    @Accessor("xpOrbColorR")
    @Nullable IEntityExp flywheel$xpOrbColorR();

    @Accessor("xpOrbColorG")
    @Nullable IEntityExp flywheel$xpOrbColorG();

    @Accessor("xpOrbColorB")
    @Nullable IEntityExp flywheel$xpOrbColorB();
}
