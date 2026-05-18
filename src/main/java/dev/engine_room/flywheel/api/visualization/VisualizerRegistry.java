package dev.engine_room.flywheel.api.visualization;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 1.12.2: registry of block-entity and entity visualizers keyed by concrete class (no
 * {@code *Type} registry exists in 1.12.2 like 1.18+'s). Per-class {@link MutableCallSite}s let
 * hot-path lookups fold to a JIT constant rather than a hash probe, which is substantially faster
 */
public final class VisualizerRegistry {
    private static final MethodType BE_SITE_TYPE = MethodType.methodType(BlockEntityVisualizer.class);
    private static final MethodType ENTITY_SITE_TYPE = MethodType.methodType(EntityVisualizer.class);

    private static final Reference2ObjectOpenHashMap<Class<? extends TileEntity>, BlockEntityVisualizer<?>> BE_VISUALIZERS = new Reference2ObjectOpenHashMap<>(256);
    private static final Reference2ObjectOpenHashMap<Class<? extends Entity>, EntityVisualizer<?>> ENTITY_VISUALIZERS = new Reference2ObjectOpenHashMap<>(256);

    private static final Reference2ObjectOpenHashMap<Class<? extends TileEntity>, MutableCallSite> BE_SITES = new Reference2ObjectOpenHashMap<>(256);
    private static final Reference2ObjectOpenHashMap<Class<? extends Entity>, MutableCallSite> ENTITY_SITES = new Reference2ObjectOpenHashMap<>(256);

    private static final ReferenceOpenHashSet<Class<?>> DISABLED = new ReferenceOpenHashSet<>();

    private VisualizerRegistry() {
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <T extends TileEntity> BlockEntityVisualizer<? super T> getBlockEntityVisualizer(Class<T> type) {
        if (DISABLED.contains(type)) return null;
        return (BlockEntityVisualizer<? super T>) BE_VISUALIZERS.get(type);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <T extends Entity> EntityVisualizer<? super T> getEntityVisualizer(Class<T> type) {
        if (DISABLED.contains(type)) return null;
        return (EntityVisualizer<? super T>) ENTITY_VISUALIZERS.get(type);
    }

    public static <T extends TileEntity> void setBlockEntityVisualizer(
            Class<T> type, @Nullable BlockEntityVisualizer<? super T> visualizer) {
        Objects.requireNonNull(type);
        if (visualizer == null) {
            BE_VISUALIZERS.remove(type);
        } else {
            BE_VISUALIZERS.put(type, visualizer);
        }
        MutableCallSite site = BE_SITES.get(type);
        if (site != null) {
            BlockEntityVisualizer<?> target = (visualizer == null || DISABLED.contains(type)) ? null : visualizer;
            site.setTarget(MethodHandles.constant(BlockEntityVisualizer.class, target));
        }
    }

    public static <T extends Entity> void setEntityVisualizer(
            Class<T> type, @Nullable EntityVisualizer<? super T> visualizer) {
        Objects.requireNonNull(type);
        if (visualizer == null) {
            ENTITY_VISUALIZERS.remove(type);
        } else {
            ENTITY_VISUALIZERS.put(type, visualizer);
        }
        MutableCallSite site = ENTITY_SITES.get(type);
        if (site != null) {
            EntityVisualizer<?> target = (visualizer == null || DISABLED.contains(type)) ? null : visualizer;
            site.setTarget(MethodHandles.constant(EntityVisualizer.class, target));
        }
    }

    /**
     * Invokedynamic bootstrap for the per-class {@code flw$visualizer()} BE override injected by
     * {@code VisualizerTransformer}. Returns the cached call site for {@code type}, creating it if
     * absent. Generated dispatch code binds to this site once and re-reads through it on each call
     * so visualizer swaps propagate without re-linking.
     */
    public static CallSite obtainBeCallSite(MethodHandles.Lookup lookup, String name,
                                            MethodType type, Class<? extends TileEntity> teClass) {
        MutableCallSite site = BE_SITES.get(teClass);
        if (site == null) {
            BlockEntityVisualizer<?> v = DISABLED.contains(teClass) ? null : BE_VISUALIZERS.get(teClass);
            site = new MutableCallSite(BE_SITE_TYPE);
            site.setTarget(MethodHandles.constant(BlockEntityVisualizer.class, v));
            BE_SITES.put(teClass, site);
        }
        return site;
    }

    /** See {@link #obtainBeCallSite}; entity variant. */
    public static CallSite obtainEntityCallSite(MethodHandles.Lookup lookup, String name,
                                                MethodType type, Class<? extends Entity> entityClass) {
        MutableCallSite site = ENTITY_SITES.get(entityClass);
        if (site == null) {
            EntityVisualizer<?> v = DISABLED.contains(entityClass) ? null : ENTITY_VISUALIZERS.get(entityClass);
            site = new MutableCallSite(ENTITY_SITE_TYPE);
            site.setTarget(MethodHandles.constant(EntityVisualizer.class, v));
            ENTITY_SITES.put(entityClass, site);
        }
        return site;
    }

    /**
     * Toggles dispatch for a registered class without removing it from the registry: a disabled
     * visualizer falls back to the vanilla renderer but is restored on re-enable. Returns
     * {@code false} if the class was never registered.
     */
    public static boolean setEnabled(Class<?> type, boolean enabled) {
        boolean beKnown = BE_VISUALIZERS.containsKey(type);
        boolean entityKnown = ENTITY_VISUALIZERS.containsKey(type);
        if (!beKnown && !entityKnown) return false;

        if (enabled) DISABLED.remove(type);
        else DISABLED.add(type);

        if (beKnown) {
            MutableCallSite site = BE_SITES.get(type);
            if (site != null) {
                BlockEntityVisualizer<?> target = enabled ? BE_VISUALIZERS.get(type) : null;
                site.setTarget(MethodHandles.constant(BlockEntityVisualizer.class, target));
            }
        }
        if (entityKnown) {
            MutableCallSite site = ENTITY_SITES.get(type);
            if (site != null) {
                EntityVisualizer<?> target = enabled ? ENTITY_VISUALIZERS.get(type) : null;
                site.setTarget(MethodHandles.constant(EntityVisualizer.class, target));
            }
        }
        return true;
    }

    public static boolean isEnabled(Class<?> type) {
        return !DISABLED.contains(type);
    }

    public static Set<Class<?>> registered() {
        HashSet<Class<?>> out = new HashSet<>(BE_VISUALIZERS.size() + ENTITY_VISUALIZERS.size());
        out.addAll(BE_VISUALIZERS.keySet());
        out.addAll(ENTITY_VISUALIZERS.keySet());
        return Collections.unmodifiableSet(out);
    }
}
