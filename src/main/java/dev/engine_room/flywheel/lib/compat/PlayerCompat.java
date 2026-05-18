package dev.engine_room.flywheel.lib.compat;

import com.fuzs.aquaacrobatics.entity.player.IPlayerResizeable;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.fml.common.Loader;

import java.util.function.Predicate;

public final class PlayerCompat {
    private static final Predicate<EntityPlayerSP> IS_SWIMMING = Loader.isModLoaded("aquaacrobatics")
            ? p -> ((IPlayerResizeable) p).isActuallySwimming()
            : _ -> false;

    public static boolean isSwimming(EntityPlayerSP player) {
        return IS_SWIMMING.test(player);
    }
}
