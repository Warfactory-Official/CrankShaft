package dev.engine_room.flywheel.backend.engine.embed;

import dev.engine_room.flywheel.backend.compile.ContextShader;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;

public record TaggedEnvironment(RenderIdentity identity) implements Environment {
    public static final int KIND_BLOCK_ENTITY = 1;
    public static final int KIND_ENTITY = 2;
    public static final int KIND_ITEM = 3;
    public static final int KIND_BLOCK_ITEM = 4;
    public static final int KIND_BORROWED_BLOCK = 5;
    public static final int KIND_ENTITY_EYES = 6;
    public static final int KIND_ENTITY_TRANSLUCENT = 7;
    public static final int KIND_ENTITY_BLENDED = 8;

    public static int tag(int kind, int id) {
        return kind << 24 | ((id + 1) & 0xFFFFFF);
    }

    public static int kind(int tag) {
        return tag >>> 24;
    }

    public static int id(int tag) {
        return (tag & 0xFFFFFF) - 1;
    }

    public static boolean isEntity(int tag) {
        var kind = kind(tag);
        return kind == KIND_ENTITY || isRoutedKind(kind);
    }

    public static boolean isRoutedKind(int kind) {
        return kind == KIND_ENTITY_EYES || kind == KIND_ENTITY_TRANSLUCENT || kind == KIND_ENTITY_BLENDED;
    }

    @Override
    public ContextShader contextShader() {
        return ContextShader.DEFAULT;
    }

    @Override
    public void setupDraw(GlProgram program) {
    }

    @Override
    public int matrixIndex() {
        return 0;
    }
}
