package dev.engine_room.flywheel.api.visualization;

import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.EntityVisual;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.*;

/**
 * 1.12.2: registry of block-entity and entity visualizers keyed by concrete class (no
 * {@code *Type} registry exists in 1.12.2 like 1.18+'s). Per-class {@link MutableCallSite}s let
 * hot-path lookups fold to a JIT constant.
 * This is profiled to be substantially faster than {@link IdentityHashMap} and {@link ClassValue}
 */
public final class VisualizerRegistry {
    private static final MethodType BE_SITE_TYPE = MethodType.methodType(BlockEntityVisualizer.class);
    private static final MethodType ENTITY_SITE_TYPE = MethodType.methodType(EntityVisualizer.class);

    private static final Reference2ObjectOpenHashMap<Class<? extends TileEntity>, BlockEntityVisualizer<?>> BE_VISUALIZERS = new Reference2ObjectOpenHashMap<>(256);
    private static final Reference2ObjectOpenHashMap<Class<? extends Entity>, EntityVisualizer<?>> ENTITY_VISUALIZERS = new Reference2ObjectOpenHashMap<>(256);

    private static final Reference2ObjectOpenHashMap<Class<? extends TileEntity>, MutableCallSite> BE_SITES = new Reference2ObjectOpenHashMap<>(256);
    private static final Reference2ObjectOpenHashMap<Class<? extends Entity>, MutableCallSite> ENTITY_SITES = new Reference2ObjectOpenHashMap<>(256);
    private static final Reference2ObjectOpenHashMap<Class<? extends TileEntity>, MutableCallSite> BE_SKIP_SITES = new Reference2ObjectOpenHashMap<>(256);
    private static final Reference2ObjectOpenHashMap<Class<? extends Entity>, MutableCallSite> ENTITY_SKIP_SITES = new Reference2ObjectOpenHashMap<>(256);
    private static final Reference2ObjectOpenHashMap<Class<? extends TileEntity>, MutableCallSite> BE_CAN_SITES = new Reference2ObjectOpenHashMap<>(256);
    private static final Reference2ObjectOpenHashMap<Class<? extends Entity>, MutableCallSite> ENTITY_CAN_SITES = new Reference2ObjectOpenHashMap<>(256);
    private static final Reference2ObjectOpenHashMap<Class<? extends TileEntity>, MutableCallSite> BE_CREATE_SITES = new Reference2ObjectOpenHashMap<>(256);
    private static final Reference2ObjectOpenHashMap<Class<? extends Entity>, MutableCallSite> ENTITY_CREATE_SITES = new Reference2ObjectOpenHashMap<>(256);

    private static final MethodType BE_SKIP_SITE_TYPE = MethodType.methodType(boolean.class, TileEntity.class);
    private static final MethodType ENTITY_SKIP_SITE_TYPE = MethodType.methodType(boolean.class, Entity.class);
    private static final MethodType CAN_SITE_TYPE = MethodType.methodType(boolean.class);
    private static final MethodType BE_CREATE_SITE_TYPE =
            MethodType.methodType(BlockEntityVisual.class, VisualizationContext.class, TileEntity.class, float.class);
    private static final MethodType ENTITY_CREATE_SITE_TYPE =
            MethodType.methodType(EntityVisual.class, VisualizationContext.class, Entity.class, float.class);

    private static final MethodHandle BE_SKIP_FALSE =
            MethodHandles.dropArguments(MethodHandles.constant(boolean.class, false), 0, TileEntity.class);
    private static final MethodHandle ENTITY_SKIP_FALSE =
            MethodHandles.dropArguments(MethodHandles.constant(boolean.class, false), 0, Entity.class);
    private static final MethodHandle CAN_TRUE = MethodHandles.constant(boolean.class, true);
    private static final MethodHandle CAN_FALSE = MethodHandles.constant(boolean.class, false);
    // createRaw treats "no visualizer" as null (a queued add can race a toggle), so the unbound
    // create target returns null rather than throwing.
    private static final MethodHandle BE_CREATE_NULL = MethodHandles.dropArguments(
            MethodHandles.constant(BlockEntityVisual.class, null), 0, VisualizationContext.class, TileEntity.class, float.class);
    private static final MethodHandle ENTITY_CREATE_NULL = MethodHandles.dropArguments(
            MethodHandles.constant(EntityVisual.class, null), 0, VisualizationContext.class, Entity.class, float.class);
    // Erased *Visualizer.skipVanillaRender / createVisual. Bound per site to the registered visualizer
    // so the receiver is a JIT constant and the implementation inlines.
    private static final MethodHandle BE_SKIP_VR;
    private static final MethodHandle ENTITY_SKIP_VR;
    private static final MethodHandle BE_CREATE_VR;
    private static final MethodHandle ENTITY_CREATE_VR;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            BE_SKIP_VR = lookup.findVirtual(BlockEntityVisualizer.class, "skipVanillaRender",
                    MethodType.methodType(boolean.class, TileEntity.class));
            ENTITY_SKIP_VR = lookup.findVirtual(EntityVisualizer.class, "skipVanillaRender",
                    MethodType.methodType(boolean.class, Entity.class));
            BE_CREATE_VR = lookup.findVirtual(BlockEntityVisualizer.class, "createVisual",
                    MethodType.methodType(BlockEntityVisual.class, VisualizationContext.class, TileEntity.class, float.class));
            ENTITY_CREATE_VR = lookup.findVirtual(EntityVisualizer.class, "createVisual",
                    MethodType.methodType(EntityVisual.class, VisualizationContext.class, Entity.class, float.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final ReferenceOpenHashSet<Class<?>> DISABLED = new ReferenceOpenHashSet<>();

    // Bootstraps may link off the main thread (FJP rebuild workers, render thread) while main-thread
    // mutators (set*Visualizer, setEnabled) race them; all access to the plain fastutil structures and
    // every setTarget batch is serialized here. The linked MethodHandle dispatch fast path stays lock-free.
    private static final Object LOCK = new Object();

    private VisualizerRegistry() {
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <T extends TileEntity> BlockEntityVisualizer<? super T> getBlockEntityVisualizer(Class<T> type) {
        synchronized (LOCK) {
            if (DISABLED.contains(type)) return null;
            return (BlockEntityVisualizer<? super T>) BE_VISUALIZERS.get(type);
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <T extends Entity> EntityVisualizer<? super T> getEntityVisualizer(Class<T> type) {
        synchronized (LOCK) {
            if (DISABLED.contains(type)) return null;
            return (EntityVisualizer<? super T>) ENTITY_VISUALIZERS.get(type);
        }
    }

    public static <T extends TileEntity> void setBlockEntityVisualizer(
            Class<T> type, @Nullable BlockEntityVisualizer<? super T> visualizer) {
        Objects.requireNonNull(type);
        synchronized (LOCK) {
            if (visualizer == null) {
                BE_VISUALIZERS.remove(type);
            } else {
                BE_VISUALIZERS.put(type, visualizer);
            }
            retargetBe(type, (visualizer == null || DISABLED.contains(type)) ? null : visualizer);
        }
    }

    // Caller holds LOCK. Retargets every existing site for type, then publishes via
    // MutableCallSite.syncAll so threads concurrently executing the sites observe the new targets.
    private static void retargetBe(Class<?> type, @Nullable BlockEntityVisualizer<?> target) {
        int n = 0;
        MutableCallSite[] touched = new MutableCallSite[4];
        MutableCallSite site = BE_SITES.get(type);
        if (site != null) {
            site.setTarget(MethodHandles.constant(BlockEntityVisualizer.class, target));
            touched[n++] = site;
        }
        MutableCallSite skip = BE_SKIP_SITES.get(type);
        if (skip != null) {
            skip.setTarget(target == null ? BE_SKIP_FALSE : BE_SKIP_VR.bindTo(target));
            touched[n++] = skip;
        }
        MutableCallSite can = BE_CAN_SITES.get(type);
        if (can != null) {
            can.setTarget(target == null ? CAN_FALSE : CAN_TRUE);
            touched[n++] = can;
        }
        MutableCallSite create = BE_CREATE_SITES.get(type);
        if (create != null) {
            create.setTarget(target == null ? BE_CREATE_NULL : BE_CREATE_VR.bindTo(target));
            touched[n++] = create;
        }
        syncTargets(touched, n);
    }

    // Caller holds LOCK. See retargetBe.
    private static void retargetEntity(Class<?> type, @Nullable EntityVisualizer<?> target) {
        int n = 0;
        MutableCallSite[] touched = new MutableCallSite[4];
        MutableCallSite site = ENTITY_SITES.get(type);
        if (site != null) {
            site.setTarget(MethodHandles.constant(EntityVisualizer.class, target));
            touched[n++] = site;
        }
        MutableCallSite skip = ENTITY_SKIP_SITES.get(type);
        if (skip != null) {
            skip.setTarget(target == null ? ENTITY_SKIP_FALSE : ENTITY_SKIP_VR.bindTo(target));
            touched[n++] = skip;
        }
        MutableCallSite can = ENTITY_CAN_SITES.get(type);
        if (can != null) {
            can.setTarget(target == null ? CAN_FALSE : CAN_TRUE);
            touched[n++] = can;
        }
        MutableCallSite create = ENTITY_CREATE_SITES.get(type);
        if (create != null) {
            create.setTarget(target == null ? ENTITY_CREATE_NULL : ENTITY_CREATE_VR.bindTo(target));
            touched[n++] = create;
        }
        syncTargets(touched, n);
    }

    private static void syncTargets(MutableCallSite[] touched, int n) {
        if (n == 0) return;
        MutableCallSite.syncAll(n == touched.length ? touched : Arrays.copyOf(touched, n));
    }

    public static <T extends Entity> void setEntityVisualizer(
            Class<T> type, @Nullable EntityVisualizer<? super T> visualizer) {
        Objects.requireNonNull(type);
        synchronized (LOCK) {
            if (visualizer == null) {
                ENTITY_VISUALIZERS.remove(type);
            } else {
                ENTITY_VISUALIZERS.put(type, visualizer);
            }
            retargetEntity(type, (visualizer == null || DISABLED.contains(type)) ? null : visualizer);
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
        synchronized (LOCK) {
            MutableCallSite site = BE_SITES.get(teClass);
            if (site == null) {
                BlockEntityVisualizer<?> v = DISABLED.contains(teClass) ? null : BE_VISUALIZERS.get(teClass);
                site = new MutableCallSite(BE_SITE_TYPE);
                site.setTarget(MethodHandles.constant(BlockEntityVisualizer.class, v));
                BE_SITES.put(teClass, site);
            }
            return site;
        }
    }

    /** See {@link #obtainEntitySkipCallSite}; BE variant. */
    public static CallSite obtainBeSkipCallSite(MethodHandles.Lookup lookup, String name,
                                                MethodType type, Class<? extends TileEntity> teClass) {
        synchronized (LOCK) {
            MutableCallSite site = BE_SKIP_SITES.get(teClass);
            if (site == null) {
                BlockEntityVisualizer<?> v = DISABLED.contains(teClass) ? null : BE_VISUALIZERS.get(teClass);
                site = new MutableCallSite(BE_SKIP_SITE_TYPE);
                site.setTarget(v == null ? BE_SKIP_FALSE : BE_SKIP_VR.bindTo(v));
                BE_SKIP_SITES.put(teClass, site);
            }
            return site;
        }
    }

    /** Bootstrap for the per-class {@code flw$canVisualize()} override: a constant boolean. */
    public static CallSite obtainBeCanCallSite(MethodHandles.Lookup lookup, String name,
                                               MethodType type, Class<? extends TileEntity> teClass) {
        synchronized (LOCK) {
            MutableCallSite site = BE_CAN_SITES.get(teClass);
            if (site == null) {
                BlockEntityVisualizer<?> v = DISABLED.contains(teClass) ? null : BE_VISUALIZERS.get(teClass);
                site = new MutableCallSite(CAN_SITE_TYPE);
                site.setTarget(v == null ? CAN_FALSE : CAN_TRUE);
                BE_CAN_SITES.put(teClass, site);
            }
            return site;
        }
    }

    /** See {@link #obtainBeCanCallSite}; entity variant. */
    public static CallSite obtainEntityCanCallSite(MethodHandles.Lookup lookup, String name,
                                                   MethodType type, Class<? extends Entity> entityClass) {
        synchronized (LOCK) {
            MutableCallSite site = ENTITY_CAN_SITES.get(entityClass);
            if (site == null) {
                EntityVisualizer<?> v = DISABLED.contains(entityClass) ? null : ENTITY_VISUALIZERS.get(entityClass);
                site = new MutableCallSite(CAN_SITE_TYPE);
                site.setTarget(v == null ? CAN_FALSE : CAN_TRUE);
                ENTITY_CAN_SITES.put(entityClass, site);
            }
            return site;
        }
    }

    /** Bootstrap for the per-class {@code flw$createVisual} override; visualizer bound as a constant
     *  receiver, or a null-returning constant when unregistered/disabled. */
    public static CallSite obtainBeCreateCallSite(MethodHandles.Lookup lookup, String name,
                                                  MethodType type, Class<? extends TileEntity> teClass) {
        synchronized (LOCK) {
            MutableCallSite site = BE_CREATE_SITES.get(teClass);
            if (site == null) {
                BlockEntityVisualizer<?> v = DISABLED.contains(teClass) ? null : BE_VISUALIZERS.get(teClass);
                site = new MutableCallSite(BE_CREATE_SITE_TYPE);
                site.setTarget(v == null ? BE_CREATE_NULL : BE_CREATE_VR.bindTo(v));
                BE_CREATE_SITES.put(teClass, site);
            }
            return site;
        }
    }

    /** See {@link #obtainBeCreateCallSite}; entity variant. */
    public static CallSite obtainEntityCreateCallSite(MethodHandles.Lookup lookup, String name,
                                                      MethodType type, Class<? extends Entity> entityClass) {
        synchronized (LOCK) {
            MutableCallSite site = ENTITY_CREATE_SITES.get(entityClass);
            if (site == null) {
                EntityVisualizer<?> v = DISABLED.contains(entityClass) ? null : ENTITY_VISUALIZERS.get(entityClass);
                site = new MutableCallSite(ENTITY_CREATE_SITE_TYPE);
                site.setTarget(v == null ? ENTITY_CREATE_NULL : ENTITY_CREATE_VR.bindTo(v));
                ENTITY_CREATE_SITES.put(entityClass, site);
            }
            return site;
        }
    }

    /** See {@link #obtainBeCallSite}; entity variant. */
    public static CallSite obtainEntityCallSite(MethodHandles.Lookup lookup, String name,
                                                MethodType type, Class<? extends Entity> entityClass) {
        synchronized (LOCK) {
            MutableCallSite site = ENTITY_SITES.get(entityClass);
            if (site == null) {
                EntityVisualizer<?> v = DISABLED.contains(entityClass) ? null : ENTITY_VISUALIZERS.get(entityClass);
                site = new MutableCallSite(ENTITY_SITE_TYPE);
                site.setTarget(MethodHandles.constant(EntityVisualizer.class, v));
                ENTITY_SITES.put(entityClass, site);
            }
            return site;
        }
    }

    /** See {@link #obtainBeCallSite}; bootstrap for the per-class {@code flw$skipVanillaRender()} override.
     *  The site target is either constant {@code false} (unregistered/disabled) or the registered
     *  visualizer's {@code skipVanillaRender} with the visualizer bound as a constant receiver, so the
     *  predicate inlines at the injected (per-class, monomorphic) call site. */
    public static CallSite obtainEntitySkipCallSite(MethodHandles.Lookup lookup, String name,
                                                    MethodType type, Class<? extends Entity> entityClass) {
        synchronized (LOCK) {
            MutableCallSite site = ENTITY_SKIP_SITES.get(entityClass);
            if (site == null) {
                EntityVisualizer<?> v = DISABLED.contains(entityClass) ? null : ENTITY_VISUALIZERS.get(entityClass);
                site = new MutableCallSite(ENTITY_SKIP_SITE_TYPE);
                site.setTarget(v == null ? ENTITY_SKIP_FALSE : ENTITY_SKIP_VR.bindTo(v));
                ENTITY_SKIP_SITES.put(entityClass, site);
            }
            return site;
        }
    }

    /**
     * Toggles dispatch for a registered class without removing it from the registry: a disabled
     * visualizer falls back to the vanilla renderer but is restored on re-enable. Returns
     * {@code false} if the class was never registered.
     */
    public static boolean setEnabled(Class<?> type, boolean enabled) {
        synchronized (LOCK) {
            boolean beKnown = BE_VISUALIZERS.containsKey(type);
            boolean entityKnown = ENTITY_VISUALIZERS.containsKey(type);
            if (!beKnown && !entityKnown) return false;

            if (enabled) DISABLED.remove(type);
            else DISABLED.add(type);

            if (beKnown) {
                retargetBe(type, enabled ? BE_VISUALIZERS.get(type) : null);
            }
            if (entityKnown) {
                retargetEntity(type, enabled ? ENTITY_VISUALIZERS.get(type) : null);
            }
            return true;
        }
    }

    public static boolean isEnabled(Class<?> type) {
        synchronized (LOCK) {
            return !DISABLED.contains(type);
        }
    }

    public static Set<Class<?>> registered() {
        synchronized (LOCK) {
            HashSet<Class<?>> out = new HashSet<>(BE_VISUALIZERS.size() + ENTITY_VISUALIZERS.size());
            out.addAll(BE_VISUALIZERS.keySet());
            out.addAll(ENTITY_VISUALIZERS.keySet());
            return Collections.unmodifiableSet(out);
        }
    }
}
