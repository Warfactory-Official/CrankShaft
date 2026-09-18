package dev.engine_room.flywheel.iris.compile;

import net.irisshaders.iris.shaderpack.loading.ProgramId;

/**
 * Pack program a guest draw resolves through Iris's fallback chain.
 */
public enum PackRole {
    SOLID(ProgramId.Block, false),
    TRANSLUCENT(ProgramId.BlockTrans, false),
    DAMAGED(ProgramId.DamagedBlock, false),
    SHADOW(ProgramId.ShadowBlock, true),
    ENTITY_SOLID(ProgramId.Entities, false),
    ENTITY_TRANSLUCENT(ProgramId.EntitiesTrans, false),
    ENTITY_SHADOW(ProgramId.ShadowEntities, true);

    final ProgramId programId;
    final boolean shadow;

    PackRole(ProgramId programId, boolean shadow) {
        this.programId = programId;
        this.shadow = shadow;
    }

    /**
     * The role for entity-visual draws ({@code TaggedEnvironment.KIND_ENTITY}).
     */
    public PackRole forEntities() {
        return switch (this) {
            case SOLID -> ENTITY_SOLID;
            case TRANSLUCENT -> ENTITY_TRANSLUCENT;
            case SHADOW -> ENTITY_SHADOW;
            default -> this;
        };
    }

    PackRole blockRole() {
        return switch (this) {
            case ENTITY_SOLID -> SOLID;
            case ENTITY_TRANSLUCENT -> TRANSLUCENT;
            case ENTITY_SHADOW -> SHADOW;
            default -> this;
        };
    }
}
