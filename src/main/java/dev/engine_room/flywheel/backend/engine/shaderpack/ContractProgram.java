package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.material.Transparency;

enum ContractProgram {
    GBUFFERS("clrwl_gbuffers", null),
    GBUFFERS_BLOCK("clrwl_gbuffers_block", GBUFFERS),
    GBUFFERS_ENTITIES("clrwl_gbuffers_entities", GBUFFERS),
    GBUFFERS_ADDITIVE("clrwl_gbuffers_additive", GBUFFERS),
    GBUFFERS_NATIVE_ADDITIVE("crankshaft_gbuffers_additive", GBUFFERS_ADDITIVE),
    GBUFFERS_NATIVE_ADDITIVE_COLOR("crankshaft_gbuffers_additive_color", GBUFFERS_NATIVE_ADDITIVE),
    GBUFFERS_LIGHTNING("clrwl_gbuffers_lightning", GBUFFERS),
    GBUFFERS_GLINT("clrwl_gbuffers_glint", GBUFFERS),
    GBUFFERS_TRANSLUCENT("clrwl_gbuffers_translucent", GBUFFERS),
    GBUFFERS_UNLIT_TRANSLUCENT("clrwl_gbuffers_unlit_translucent", GBUFFERS_TRANSLUCENT),
    GBUFFERS_DAMAGEDBLOCK("clrwl_gbuffers_damagedblock", GBUFFERS),
    SHADOW("clrwl_shadow", null),
    SHADOW_ADDITIVE("clrwl_shadow_additive", SHADOW),
    SHADOW_LIGHTNING("clrwl_shadow_lightning", null),
    SHADOW_GLINT("clrwl_shadow_glint", SHADOW),
    SHADOW_TRANSLUCENT("clrwl_shadow_translucent", SHADOW);

    final String sourceName;
    final ContractProgram base;

    ContractProgram(String sourceName, ContractProgram base) {
        this.sourceName = sourceName;
        this.base = base;
    }

    static ContractProgram of(Transparency transparency, boolean shadow, boolean crumbling) {
        if (crumbling) return shadow ? SHADOW : GBUFFERS_DAMAGEDBLOCK;
        return switch (transparency) {
            case OPAQUE -> shadow ? SHADOW : GBUFFERS_BLOCK;
            case ADDITIVE -> shadow ? SHADOW_ADDITIVE : GBUFFERS_ADDITIVE;
            case LIGHTNING -> shadow ? SHADOW_LIGHTNING : GBUFFERS_LIGHTNING;
            case GLINT -> shadow ? SHADOW_GLINT : GBUFFERS_GLINT;
            case CRUMBLING -> shadow ? SHADOW : GBUFFERS_DAMAGEDBLOCK;
            case TRANSLUCENT, ORDER_INDEPENDENT -> shadow ? SHADOW_TRANSLUCENT : GBUFFERS_TRANSLUCENT;
        };
    }

    GuestRole nativePass() {
        return switch (this) {
            case GBUFFERS -> GuestRole.GBUFFERS_TERRAIN;
            case GBUFFERS_BLOCK -> GuestRole.GBUFFERS_BLOCK;
            case GBUFFERS_ENTITIES -> GuestRole.GBUFFERS_ENTITIES;
            case GBUFFERS_ADDITIVE, GBUFFERS_NATIVE_ADDITIVE, GBUFFERS_NATIVE_ADDITIVE_COLOR ->
                    GuestRole.GBUFFERS_BEACONBEAM;
            case GBUFFERS_LIGHTNING -> GuestRole.GBUFFERS_LIGHTNING;
            case GBUFFERS_GLINT -> GuestRole.GBUFFERS_ARMOR_GLINT;
            case GBUFFERS_TRANSLUCENT, GBUFFERS_UNLIT_TRANSLUCENT -> GuestRole.GBUFFERS_WATER;
            case GBUFFERS_DAMAGEDBLOCK -> GuestRole.GBUFFERS_DAMAGEDBLOCK;
            default -> GuestRole.SHADOW;
        };
    }
}
