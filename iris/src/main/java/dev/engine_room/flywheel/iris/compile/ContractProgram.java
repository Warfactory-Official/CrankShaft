package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.api.material.Transparency;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Colorwheel pack programs: a pack that ships {@code clrwl_gbuffers} opts every guest draw into the
 * {@code clrwl_computeFragment} contract; a program it lacks resolves through {@link #base}.
 */
public enum ContractProgram {
    GBUFFERS("clrwl_gbuffers", null, false),
    // CrankShaft extension (Colorwheel has none): opaque entity-tagged draws; ContractPatches builds it for known packs.
    GBUFFERS_ENTITIES("clrwl_gbuffers_entities", GBUFFERS, false),
    GBUFFERS_ADDITIVE("clrwl_gbuffers_additive", GBUFFERS, false),
    GBUFFERS_GLINT("clrwl_gbuffers_glint", GBUFFERS, false),
    GBUFFERS_LIGHTNING("clrwl_gbuffers_lightning", GBUFFERS, false),
    GBUFFERS_TRANSLUCENT("clrwl_gbuffers_translucent", GBUFFERS, false),
    GBUFFERS_DAMAGEDBLOCK("clrwl_gbuffers_damagedblock", GBUFFERS, false),
    SHADOW("clrwl_shadow", null, true),
    SHADOW_ADDITIVE("clrwl_shadow_additive", SHADOW, true),
    SHADOW_GLINT("clrwl_shadow_glint", SHADOW, true),
    SHADOW_LIGHTNING("clrwl_shadow_lightning", null, true),
    SHADOW_TRANSLUCENT("clrwl_shadow_translucent", SHADOW, true);

    public final String sourceName;
    public final @Nullable ContractProgram base;
    final boolean shadow;

    ContractProgram(String sourceName, @Nullable ContractProgram base, boolean shadow) {
        this.sourceName = sourceName;
        this.base = base;
        this.shadow = shadow;
    }

    /**
     * {@code ships}: the pack has that program itself, ignoring {@link #base}.
     */
    static ContractProgram of(PackRole role, Transparency transparency, Predicate<ContractProgram> ships) {
        if (role == PackRole.DAMAGED) {
            return GBUFFERS_DAMAGEDBLOCK;
        }
        if (role == PackRole.ENTITY_SOLID && transparency == Transparency.OPAQUE) {
            return GBUFFERS_ENTITIES;
        }
        // Iris resolves EntitiesTrans through Entities. Absent an entity program the entity chain would land on the
        // opaque GBUFFERS, so fall through to the pack's translucent program instead. ORDER_INDEPENDENT always stays
        // there: Colorwheel defines OIT on that program and the accumulate targets are sized from its draw buffers.
        if (role == PackRole.ENTITY_TRANSLUCENT
                && (transparency == Transparency.TRANSLUCENT
                || transparency == Transparency.TRANSLUCENT_ALPHA_REPLACE)
                && ships.test(GBUFFERS_ENTITIES)) {
            return GBUFFERS_ENTITIES;
        }
        boolean shadow = role.shadow;
        return switch (transparency) {
            case OPAQUE -> shadow ? SHADOW : GBUFFERS;
            case ADDITIVE -> shadow ? SHADOW_ADDITIVE : GBUFFERS_ADDITIVE;
            // ORDER_INDEPENDENT_ADDITIVE: no guest emission chain => the Transparency fallback, and the pipeline
            // already blends it as LIGHTNING.
            case LIGHTNING, ORDER_INDEPENDENT_ADDITIVE -> shadow ? SHADOW_LIGHTNING : GBUFFERS_LIGHTNING;
            case GLINT -> shadow ? SHADOW_GLINT : GBUFFERS_GLINT;
            case CRUMBLING -> shadow ? SHADOW : GBUFFERS_DAMAGEDBLOCK;
            case TRANSLUCENT, TRANSLUCENT_ALPHA_REPLACE, ORDER_INDEPENDENT ->
                    shadow ? SHADOW_TRANSLUCENT : GBUFFERS_TRANSLUCENT;
        };
    }

    public static @Nullable ContractProgram byName(String sourceName) {
        for (ContractProgram program : values()) {
            if (program.sourceName.equals(sourceName)) {
                return program;
            }
        }
        return null;
    }
}
