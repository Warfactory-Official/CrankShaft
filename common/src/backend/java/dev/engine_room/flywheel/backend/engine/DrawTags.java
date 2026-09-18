package dev.engine_room.flywheel.backend.engine;

/**
 * Shaderpack guest per-draw tags, fixed at draw creation: {@code drawTag} in {@link
 * dev.engine_room.flywheel.backend.engine.embed.TaggedEnvironment} layout, {@code itemTag} the same layout with an item
 * kind ({@code 0} = none). Native draws carry none.
 */
public record DrawTags(int drawTag, int itemTag) {
}
