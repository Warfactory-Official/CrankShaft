package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment;
import net.irisshaders.iris.shaderpack.loading.ProgramId;

/**
 * Pack program a guest draw resolves through Iris's fallback chain.
 */
public enum PackRole {
    SOLID(ProgramId.Block, false),
    BLOCK_ENTITY(ProgramId.Block, false),
    // Borrowed block ids ride mc_Entity, which packs read in their terrain programs.
    TERRAIN(ProgramId.TerrainCutout, false),
    TRANSLUCENT(ProgramId.BlockTrans, false),
    ADDITIVE(ProgramId.BeaconBeam, false),
    DAMAGED(ProgramId.DamagedBlock, false),
    SHADOW(ProgramId.ShadowBlock, true),
    ENTITY_SOLID(ProgramId.Entities, false),
    ENTITY_TRANSLUCENT(ProgramId.EntitiesTrans, false),
    ENTITY_SHADOW(ProgramId.ShadowEntities, true),
    // Colorwheel defines none of these: they resolve the pack's own program.
    EYES(ProgramId.SpiderEyes, false),
    GLINT(ProgramId.ArmorGlint, false),
    ENTITIES_TRANSLUCENT(ProgramId.EntitiesTrans, false),
    ENTITIES(ProgramId.Entities, false);

    final ProgramId programId;
    final boolean shadow;

    PackRole(ProgramId programId, boolean shadow) {
        this.programId = programId;
        this.shadow = shadow;
    }

    /**
     * The role for draws of a {@link TaggedEnvironment} kind.
     */
    public PackRole forKind(int kind) {
        return switch (kind) {
            case TaggedEnvironment.KIND_ENTITY -> switch (this) {
                case SOLID -> ENTITY_SOLID;
                case TRANSLUCENT -> ENTITY_TRANSLUCENT;
                case SHADOW -> ENTITY_SHADOW;
                default -> this;
            };
            case TaggedEnvironment.KIND_BLOCK_ENTITY -> this == SOLID ? BLOCK_ENTITY : this;
            case TaggedEnvironment.KIND_BORROWED_BLOCK -> this == SOLID ? TERRAIN : this;
            case TaggedEnvironment.KIND_ENTITY_EYES -> shadow ? ENTITY_SHADOW : EYES;
            case TaggedEnvironment.KIND_ENTITY_TRANSLUCENT -> shadow ? ENTITY_SHADOW : ENTITIES_TRANSLUCENT;
            case TaggedEnvironment.KIND_ENTITY_BLENDED -> shadow ? ENTITY_SHADOW : ENTITIES;
            default -> this;
        };
    }

    /**
     * {@code GLINT} for a glint draw outside the shadow pass.
     */
    public PackRole forMaterial(Material material) {
        return !shadow && material.transparency() == Transparency.GLINT ? GLINT : this;
    }

    PackRole blockRole() {
        return switch (this) {
            case ENTITY_SOLID, BLOCK_ENTITY, TERRAIN -> SOLID;
            case ENTITY_TRANSLUCENT, ENTITIES_TRANSLUCENT -> TRANSLUCENT;
            case ENTITY_SHADOW -> SHADOW;
            default -> this;
        };
    }
}
