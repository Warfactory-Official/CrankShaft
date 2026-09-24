package dev.engine_room.flywheel.impl.compat;

import dev.engine_room.flywheel.impl.mixin.PolytoneColorManagerAccessor;
import dev.engine_room.flywheel.impl.mixin.PolytoneEntityModifiersAccessor;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import net.mehvahdjukaar.polytone.Polytone;
import net.mehvahdjukaar.polytone.content.entity.IRenderStateWithId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jspecify.annotations.Nullable;
import traben.entity_texture_features.ETFApi;
import traben.entity_texture_features.config.ETFConfig;
import traben.entity_texture_features.features.ETFManager;
import traben.entity_texture_features.features.texture_handlers.ETFTexture;
import traben.entity_texture_features.utils.ETFUtils2;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Compat with Entity Texture Features, Entity Model Features and Polytone: an entity type they restyle past what its
 * visual reproduces goes back to vanilla until the next renderer reload. Their per-entity state is mounted only
 * inside vanilla submission, which visuals skip. ETF, Polytone: visuals report what they draw, from any thread; the
 * checks read render-thread-only state, so they run between frames and a type flips for visual and vanilla alike.
 * EMF: entity and block entity types whose renderer construction baked one of its roots, recorded in EMF's own naming
 * context (per-type thread-local names, construction order) before any visual exists.
 */
public final class EntityFeatureCompat {
    private static final boolean ETF = CompatMod.ENTITY_TEXTURE_FEATURES.isLoaded;
    private static final boolean EMF = CompatMod.ENTITY_MODEL_FEATURES.isLoaded;
    private static final boolean POLYTONE = CompatMod.POLYTONE.isLoaded;
    public static final boolean ACTIVE = ETF || EMF || POLYTONE;
    private static final RendererReloadCache<Boolean, State> STATE = new RendererReloadCache<>($ -> new State());
    private static final ThreadLocal<boolean @Nullable []> EMF_ROOT = new ThreadLocal<>();
    private static volatile Set<EntityType<?>> emfEntities = Set.of();
    private static volatile Set<BlockEntityType<?>> emfBlockEntities = Set.of();

    private EntityFeatureCompat() {
    }

    public static boolean vanillaOwns(EntityType<?> type) {
        return ACTIVE && (emfEntities.contains(type) || STATE.get(true).vanilla.contains(type));
    }

    public static boolean vanillaOwns(BlockEntityType<?> type) {
        return EMF && emfBlockEntities.contains(type);
    }

    /**
     * Whole-type restyles: ETF render layer overrides; Polytone model particle emitters, XP orb colour, painting render
     * type.
     */
    public static void observe(EntityType<?> type) {
        if (ACTIVE) {
            resolve(type, type, EntityFeatureCompat::restyles);
        }
    }

    /**
     * ETF variants, emissive, blink or enchant layers for a texture the visual draws.
     */
    public static void observeTexture(EntityType<?> type, @Nullable Identifier texture) {
        if (ETF && texture != null) {
            resolve(type, texture, Etf::manages);
        }
    }

    /**
     * Whether {@code create}, one renderer's construction, baked an EMF root. Render thread, renderer reload.
     */
    public static boolean bakesEmfRoot(Runnable create) {
        boolean[] outer = EMF_ROOT.get();
        boolean[] baked = {false};
        EMF_ROOT.set(baked);
        try {
            create.run();
        } finally {
            EMF_ROOT.set(outer);
        }
        return baked[0];
    }

    public static void emfRootBaked() {
        boolean[] baked = EMF_ROOT.get();
        if (baked != null) {
            baked[0] = true;
        }
    }

    public static void emfRestyled(Set<EntityType<?>> types) {
        emfEntities = Set.copyOf(types);
    }

    public static void emfRestyledBlockEntities(Set<BlockEntityType<?>> types) {
        emfBlockEntities = Set.copyOf(types);
    }

    /**
     * Polytone keys its per-entity lookups on an id that {@code createRenderState(Entity, float)} sets.
     */
    public static void setStateId(EntityRenderState state, Entity entity) {
        if (POLYTONE) {
            PolytoneInternals.setStateId(state, entity);
        }
    }

    public static boolean entityShadowsOff() {
        return POLYTONE && PolytoneInternals.shadowsDisabled();
    }

    // Zero casts no shadow instances.
    public static float shadowStrength(float strength) {
        return entityShadowsOff() ? 0.0F : strength;
    }

    private static <K> void resolve(EntityType<?> type, K key, Predicate<K> test) {
        State state = STATE.get(true);
        if (state.vanilla.contains(type) || state.results.get(key) == Boolean.FALSE
                || !state.pending.add(new Observation(type, key))) {
            return;
        }
        Minecraft.getInstance().execute(() -> {
            if (state.results.computeIfAbsent(key, $ -> test.test(key))) {
                state.vanilla.add(type);
            }
        });
    }

    private static boolean restyles(EntityType<?> type) {
        return ETF && Etf.overridesRenderLayer(type) || POLYTONE && PolytoneInternals.restyles(type);
    }

    private static final class State {
        final Set<EntityType<?>> vanilla = ConcurrentHashMap.newKeySet();
        final Map<Object, Boolean> results = new ConcurrentHashMap<>();
        final Set<Observation> pending = ConcurrentHashMap.newKeySet();
    }

    private record Observation(EntityType<?> type, Object key) {
    }

    private static final class Etf {
        // ETFTextureVariator.of and the texture's own emissive/blink/enchant files.
        static boolean manages(Identifier texture) {
            ETFConfig config = ETFApi.getETFConfigObject();
            ETFTexture etf = ETFManager.getInstance().getETFTextureNoVariation(texture);
            return config.canDoCustomTextures() && (etf.isEnchanted() || etf.doesBlink()
                    || ETFApi.getVariantSupplierOrNull(ETFUtils2.replaceIdentifier(texture, ".png", ".properties"),
                    texture, "skins", "textures") != null) || config.canDoEmissiveTextures() && etf.isEmissive();
        }

        static boolean overridesRenderLayer(EntityType<?> type) {
            return ETFApi.getETFConfigObject().entityRenderLayerOverrides.containsKey(type.getDescriptionId());
        }
    }

    private static final class PolytoneInternals {
        static void setStateId(EntityRenderState state, Entity entity) {
            ((IRenderStateWithId) state).polytone$setId(entity.getId());
        }

        static boolean shadowsDisabled() {
            return Polytone.COLORS.areEntityShadowsDisabled();
        }

        static boolean restyles(EntityType<?> type) {
            if (((PolytoneEntityModifiersAccessor) Polytone.ENTITY_MODIFIERS).flywheel$emitters().containsKey(type)) {
                return true;
            }
            if (type == EntityTypes.PAINTING) {
                return Polytone.COLORS.getPaintingRenderType() != null;
            }
            if (type == EntityTypes.EXPERIENCE_ORB) {
                var colors = (PolytoneColorManagerAccessor) Polytone.COLORS;
                return colors.flywheel$xpOrbColor() != null || colors.flywheel$xpOrbColorR() != null
                        || colors.flywheel$xpOrbColorG() != null || colors.flywheel$xpOrbColorB() != null;
            }
            return false;
        }
    }
}
