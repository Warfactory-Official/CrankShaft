package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.api.layout.*;
import dev.engine_room.flywheel.backend.gl.GlNumericType;
import dev.engine_room.flywheel.backend.gl.array.VertexAttribute;

import java.util.ArrayList;
import java.util.List;

public class LayoutAttributes {
    public static List<VertexAttribute> attributes(Layout layout) {
        List<VertexAttribute> out = new ArrayList<>();

        for (Layout.Element element : layout.elements()) {
            element(out, element.type());
        }

        return out;
    }

    private static void element(List<VertexAttribute> out, ElementType type) {
        switch (type) {
            case ScalarElementType scalar -> vector(out, scalar.repr(), 1);
            case VectorElementType vector -> vector(out, vector.repr(), vector.size());
            case MatrixElementType matrix -> matrix(out, matrix);
            case ArrayElementType array -> array(out, array);
            case null, default -> throw new IllegalArgumentException("Unknown type " + type);
        }
    }

    private static void vector(List<VertexAttribute> out, ValueRepr repr, int size) {
        switch (repr) {
            case IntegerRepr integer -> out.add(new VertexAttribute.Int(toGlType(integer), size));
            case UnsignedIntegerRepr integer -> out.add(new VertexAttribute.Int(toGlType(integer), size));
            case FloatRepr floatRepr ->
                    out.add(new VertexAttribute.Float(toGlType(floatRepr), size, isNormalized(floatRepr)));
            case null, default -> throw new IllegalArgumentException("Unknown repr " + repr);
        }
    }

    private static void matrix(List<VertexAttribute> out, MatrixElementType matrix) {
        int size = matrix.columns();
        var repr = matrix.repr();
        var glType = toGlType(repr);
        boolean normalized = isNormalized(repr);

        for (int i = 0; i < matrix.rows(); i++) {
            out.add(new VertexAttribute.Float(glType, size, normalized));
        }
    }

    private static void array(List<VertexAttribute> out, ArrayElementType array) {
        ElementType innerType = array.innerType();
        int length = array.length();

        for (int i = 0; i < length; i++) {
            element(out, innerType);
        }
    }

    private static GlNumericType toGlType(IntegerRepr repr) {
        return switch (repr) {
            case BYTE -> GlNumericType.BYTE;
            case SHORT -> GlNumericType.SHORT;
            case INT -> GlNumericType.INT;
        };
    }

    private static GlNumericType toGlType(UnsignedIntegerRepr repr) {
        return switch (repr) {
            case UNSIGNED_BYTE -> GlNumericType.UBYTE;
            case UNSIGNED_SHORT -> GlNumericType.USHORT;
            case UNSIGNED_INT -> GlNumericType.UINT;
        };
    }

    private static GlNumericType toGlType(FloatRepr repr) {
        return switch (repr) {
            case BYTE, NORMALIZED_BYTE -> GlNumericType.BYTE;
            case UNSIGNED_BYTE, NORMALIZED_UNSIGNED_BYTE -> GlNumericType.UBYTE;
            case SHORT, NORMALIZED_SHORT -> GlNumericType.SHORT;
            case UNSIGNED_SHORT, NORMALIZED_UNSIGNED_SHORT -> GlNumericType.USHORT;
            case INT, NORMALIZED_INT -> GlNumericType.INT;
            case UNSIGNED_INT, NORMALIZED_UNSIGNED_INT -> GlNumericType.UINT;
            case FLOAT -> GlNumericType.FLOAT;
        };
    }

    private static boolean isNormalized(FloatRepr repr) {
        return switch (repr) {
            case NORMALIZED_BYTE, NORMALIZED_UNSIGNED_BYTE, NORMALIZED_SHORT, NORMALIZED_UNSIGNED_SHORT, NORMALIZED_INT, NORMALIZED_UNSIGNED_INT ->
                    true;
            default -> false;
        };
    }
}
