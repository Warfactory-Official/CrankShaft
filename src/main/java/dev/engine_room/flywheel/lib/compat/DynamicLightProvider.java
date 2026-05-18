package dev.engine_room.flywheel.lib.compat;

import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.Loader;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.LongPredicate;

/**
 * Abstraction over dynamic-light-providing mods.
 */
@ApiStatus.Internal
public interface DynamicLightProvider {
    DynamicLightProvider NOOP = () -> false;
    DynamicLightProvider INSTANCE = pick();
    boolean ANY_LOADED = INSTANCE != NOOP;

    private static DynamicLightProvider pick() {
        if (Loader.isModLoaded("celeritasdynamiclights")) {
            return CeleriatsDynamicLights.INSTANCE;
        } else if (FMLClientHandler.instance().hasOptifine()) {
            return OptifineDynamicLights.INSTANCE;
        }
        return NOOP;
    }

    /**
     * True iff the backing mod is loaded AND its runtime config currently has dynamic lights
     * enabled. Each entry point also short-circuits on its own — config can flip mid-frame.
     * {@link #NOOP} returns false unconditionally.
     */
    boolean enabled();

    /**
     * Snapshot the provider's source state into thread-shared storage. Invoked on the render
     * (main) thread before {@code beginFrame} submits the framePlan, so workers can read the
     * snapshot lock-free. Providers whose source data is already main-thread-quiescent during
     * the framePlan (e.g. CDL) leave this as a no-op.
     */
    default void captureSnapshot() {
    }

    /**
     * Notify the visualization manager of sections any dynamic light from this provider touches
     * (or just left) since the last call. {@code isVisualized} skips sections without any
     * {@code LightUpdatedVisual} — saves the 3-storage fanout + bake halo expansion for empties.
     */
    default void notifyAffected(LongPredicate isVisualized, VisualizationManagerImpl manager) {
    }

    /**
     * Apply this provider's dynamic-light bump to the section's CPU-side arena slot. Called
     * per just-baked section from {@code LightStorage}'s apply ForEachPlan; the slot is then
     * uploaded to the GPU light buffer.
     */
    default void applyToSection(long lightBase, int sectionX, int sectionY, int sectionZ) {
    }

    /**
     * Apply this provider's bump to a packed lightmap value at the given world position. Used
     * by visual {@code computePackedLight} paths reading {@code World.getCombinedLight}.
     * Default returns the input unchanged — providers that natively patch {@code WorldClient}
     * (e.g. OF) leave this default to avoid double-application.
     */
    default int applyLightAt(BlockPos pos, int packedLight) {
        return packedLight;
    }

    /**
     * Fully bumped lightmap for an entity at {@code samplePos} (the visual's interpolated sample
     * point, not the entity's tick-snapped {@code entity.posX/Y/Z}). Default floors burning
     * entities to 15 to match Flywheel upstream and returns the vanilla lightmap unchanged —
     * providers that natively patch {@code WorldClient.getCombinedLight} (OF) get their bump
     * implicitly. Providers without that patch (CDL) override to apply the bump and drop the
     * vanilla burning floor to avoid double-application against their own per-entity luminance.
     */
    default int getLightForEntity(Entity entity, BlockPos samplePos) {
        return entity.world.getCombinedLight(samplePos, entity.isBurning() ? 15 : 0);
    }
}
