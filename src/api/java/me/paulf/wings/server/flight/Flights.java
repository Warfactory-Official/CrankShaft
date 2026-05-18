package me.paulf.wings.server.flight;

import net.minecraft.entity.player.EntityPlayer;
import org.jspecify.annotations.Nullable;

public final class Flights {
    private Flights() {
    }

    @Nullable
    public static Flight get(EntityPlayer player) {
        throw new AssertionError();
    }
}
