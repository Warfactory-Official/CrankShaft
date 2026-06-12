package dev.engine_room.flywheel.backend.gl.array;

import dev.engine_room.flywheel.backend.gl.GlNumericType;

public sealed interface VertexAttribute {
    int byteWidth();

    record Float(GlNumericType type, int size, boolean normalized) implements VertexAttribute {
        @Override
        public int byteWidth() {
            return size * type.byteWidth();
        }
    }

    record Int(GlNumericType type, int size) implements VertexAttribute {
        @Override
        public int byteWidth() {
            return size * type.byteWidth();
        }
    }
}
