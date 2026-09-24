package dev.engine_room.flywheel.impl.compat;

import dev.tr7zw.entityculling.EntityCullingModBase;
import dev.tr7zw.entityculling.NMSCullingHelper;
import dev.tr7zw.entityculling.access.Cullable;
import net.minecraft.world.entity.Entity;

/**
 * Entity Culling clears an entity's out-of-camera flag from its {@code LevelExtractor.extractEntity} hook, which
 * visualized entities never reach; left set, it runs only a minimal client tick for them.
 */
public final class EntityCullingCompat {
    public static final boolean ACTIVE = CompatMod.ENTITY_CULLING.isLoaded;

    private EntityCullingCompat() {
    }

    /**
     * A visualized entity passed the vanilla visibility test this frame.
     */
    public static void markVisible(Entity entity) {
        if (ACTIVE) {
            Internals.markVisible(entity);
        }
    }

    private static final class Internals {
        // Entity Culling's own extractEntity condition.
        static void markVisible(Entity entity) {
            if (EntityCullingModBase.instance.config.skipEntityCulling) {
                return;
            }
            Cullable cullable = (Cullable) entity;
            if (cullable.isForcedVisible() || !cullable.isCulled() || NMSCullingHelper.ignoresCulling(entity)) {
                cullable.setOutOfCamera(false);
            }
        }
    }
}
