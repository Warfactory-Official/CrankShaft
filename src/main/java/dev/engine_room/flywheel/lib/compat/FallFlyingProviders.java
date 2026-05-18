package dev.engine_room.flywheel.lib.compat;

import me.paulf.wings.server.flight.Flight;
import me.paulf.wings.server.flight.Flights;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.fml.common.Loader;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Extension point for the {@code flw_isFallFlying} uniform slot. Vanilla {@code isElytraFlying()}
 * is hardcoded as the baseline; consumer mods register additional predicates (jetpacks, glider
 * items, etc.) that OR with the baseline.
 *
 * <p>Register from {@code preInit}:
 * <pre>{@code FallFlyingProviders.register(p -> MyJetpack.isFlying(p));}</pre>
 *
 * <p>Order doesn't matter (OR semantics). Predicates must not throw — exceptions propagate into
 * the render frame.
 */
public final class FallFlyingProviders {
    private static final List<Predicate<EntityPlayerSP>> PROVIDERS = new ArrayList<>();

    static {
        if (Loader.isModLoaded("wings")) {
            FallFlyingProviders.register(player -> {
                Flight flight = Flights.get(player);
                return flight != null && flight.isFlying();
            });
        }
    }

    private FallFlyingProviders() {
    }

    public static void register(Predicate<EntityPlayerSP> provider) {
        PROVIDERS.add(provider);
    }

    public static boolean test(EntityPlayerSP player) {
        if (player.isElytraFlying()) return true;
        for (Predicate<EntityPlayerSP> provider : PROVIDERS) {
            if (provider.test(player)) {
                return true;
            }
        }
        return false;
    }
}
