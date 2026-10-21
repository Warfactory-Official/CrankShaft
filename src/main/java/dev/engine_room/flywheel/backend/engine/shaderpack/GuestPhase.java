package dev.engine_room.flywheel.backend.engine.shaderpack;

enum GuestPhase {
    TERRAIN_SOLID(GuestRole.GBUFFERS_TERRAIN_SOLID, GuestRole.SHADOW_SOLID),
    TERRAIN_CUTOUT_MIPPED(GuestRole.GBUFFERS_TERRAIN_CUTOUT_MIP, GuestRole.SHADOW_CUTOUT),
    TERRAIN_CUTOUT(GuestRole.GBUFFERS_TERRAIN_CUTOUT, GuestRole.SHADOW_CUTOUT),
    TERRAIN_TRANSLUCENT(GuestRole.GBUFFERS_WATER, GuestRole.SHADOW_WATER),
    BLOCK_ENTITIES(GuestRole.GBUFFERS_BLOCK, GuestRole.SHADOW_BLOCK),
    BLOCK_ENTITIES_TRANSLUCENT(GuestRole.GBUFFERS_BLOCK_TRANSLUCENT, GuestRole.SHADOW_BLOCK),
    ENTITIES(GuestRole.GBUFFERS_ENTITIES, GuestRole.SHADOW_ENTITIES),
    ENTITIES_TRANSLUCENT(GuestRole.GBUFFERS_ENTITIES_TRANSLUCENT, GuestRole.SHADOW_ENTITIES),
    DESTROY(GuestRole.GBUFFERS_DAMAGEDBLOCK, GuestRole.SHADOW),
    BEACON_BEAM(GuestRole.GBUFFERS_BEACONBEAM, GuestRole.SHADOW_ENTITIES),
    LIGHTNING(GuestRole.GBUFFERS_LIGHTNING, GuestRole.SHADOW_LIGHTNING),
    ARMOR_GLINT(GuestRole.GBUFFERS_ARMOR_GLINT, GuestRole.SHADOW_ENTITIES),
    SPIDER_EYES(GuestRole.GBUFFERS_SPIDEREYES, GuestRole.SHADOW_ENTITIES);

    private final GuestRole main;
    private final GuestRole shadow;

    GuestPhase(GuestRole main, GuestRole shadow) {
        this.main = main;
        this.shadow = shadow;
    }

    GuestRole mainPass() {
        return main;
    }

    GuestRole shadowPass() {
        return shadow;
    }
}
