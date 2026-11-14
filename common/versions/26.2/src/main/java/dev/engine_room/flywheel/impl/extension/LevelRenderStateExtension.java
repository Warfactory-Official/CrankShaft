package dev.engine_room.flywheel.impl.extension;

/**
 * 26.2 counterpart of 26.3's {@code LevelRenderState.worldPartialTicks}: the partial tick the frame was extracted
 * with, recorded by {@code LevelExtractor.extract}.
 */
public interface LevelRenderStateExtension {
    float flywheel$worldPartialTicks();

    void flywheel$worldPartialTicks(float partialTick);
}
