package dev.engine_room.flywheel.backend.engine.embed;

import dev.engine_room.flywheel.backend.compile.ContextShader;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;

/**
 * {@link GlobalEnvironment} carrying a shaderpack guest's per-visual draw tag: equal tags share instancers and
 * draws. Tag layout: {@code kind << 24 | (id + 1)}, {@code 0} = untagged. Item kinds tag {@link
 * dev.engine_room.flywheel.backend.engine.DrawTags#itemTag}.
 */
public record TaggedEnvironment(int drawTag) implements Environment {
    public static final int KIND_BLOCK_ENTITY = 1;
    public static final int KIND_ENTITY = 2;
    public static final int KIND_ITEM = 3;
    // Iris also reports blockEntityId 1 for these.
    public static final int KIND_BLOCK_ITEM = 4;
    public static final int KIND_BORROWED_BLOCK = 5;
    // An entity's ids, drawn through the pack's spidereyes program.
    public static final int KIND_ENTITY_EYES = 6;
    // An entity's ids, drawn after the pack's deferred passes through its entities_translucent program.
    public static final int KIND_ENTITY_TRANSLUCENT = 7;
    // An entity's ids, blended after the pack's deferred passes through its entities program.
    public static final int KIND_ENTITY_BLENDED = 8;
    // An entity's ids, drawn as vanilla's translucent particles through the pack's particles_translucent program.
    public static final int KIND_ENTITY_PARTICLES_TRANSLUCENT = 9;

    public static int tag(int kind, int id) {
        return kind << 24 | ((id + 1) & 0xFFFFFF);
    }

    public static int kind(int drawTag) {
        return drawTag >>> 24;
    }

    public static int id(int drawTag) {
        return (drawTag & 0xFFFFFF) - 1;
    }

    public static boolean isEntity(int drawTag) {
        int kind = kind(drawTag);
        return kind == KIND_ENTITY || kind == KIND_ENTITY_EYES || kind == KIND_ENTITY_TRANSLUCENT
                || kind == KIND_ENTITY_BLENDED || kind == KIND_ENTITY_PARTICLES_TRANSLUCENT;
    }

    @Override
    public ContextShader contextShader() {
        return ContextShader.DEFAULT;
    }

    @Override
    public void setupDraw(GlProgram _) {
    }

    @Override
    public int matrixIndex() {
        return 0;
    }
}
