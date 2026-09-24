package dev.engine_room.flywheel.api.visualization;

import dev.engine_room.flywheel.api.internal.FlwApiLink;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;

/**
 * Implemented by an {@link EntityRenderer} or {@link BlockEntityRenderer} whose render-state creation and extraction
 * may run on Flywheel worker threads, concurrently with other extractions, while the render thread waits.
 * <p>
 * Contract: reads only the extracted object, its level, other entities and state fixed at construction; writes only
 * the render state (idempotent lazy caches excepted); never touches GL, textures, fonts or other render-thread-only
 * objects. Subclasses inherit the claim.
 * <p>
 * Consumed each frame by vanilla entity and block entity extraction and by entity visuals that capture render states,
 * all of which fall back to the render thread when {@code supports} is {@code false}.
 * <p>
 * Other mods' mixins on a supported renderer's extraction or on {@code EntityRenderDispatcher.extractEntity}, and
 * hooks reading the state past HEAD of {@code LevelExtractor.extractEntity} or {@code tryExtractRenderState}, are
 * assumed to keep this contract. Otherwise behaviour is undefined; the user disables concurrent extraction.
 */
public interface ConcurrentRenderStateExtraction {
    /**
     * {@code true} for implementors and for vanilla renderers not known to touch render-thread-only state; always
     * {@code false} while the user has concurrent extraction disabled.
     */
    static boolean supports(EntityRenderer<?, ?> renderer) {
        return FlwApiLink.INSTANCE.supportsConcurrentExtraction(renderer);
    }

    /**
     * As {@link #supports(EntityRenderer)}.
     */
    static boolean supports(BlockEntityRenderer<?, ?> renderer) {
        return FlwApiLink.INSTANCE.supportsConcurrentExtraction(renderer);
    }
}
