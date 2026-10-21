package com.luna.ausm.api.pipeline.shader;

public enum WorldRenderingPhase {
    BLOCK_ENTITIES,
    TERRAIN_TRANSLUCENT,
    DESTROY,
    BEACON_BEAM,
    LIGHTNING,
    ARMOR_GLINT,
    TERRAIN_SOLID,
    TERRAIN_CUTOUT,
    TERRAIN_CUTOUT_MIPPED,
    ENTITIES,
    ENTITIES_TRANSLUCENT,
    BLOCK_ENTITIES_TRANSLUCENT,
    SPIDER_EYES;

    public RenderPass mainPass() {
        throw new UnsupportedOperationException();
    }

    public RenderPass shadowPass() {
        throw new UnsupportedOperationException();
    }
}
