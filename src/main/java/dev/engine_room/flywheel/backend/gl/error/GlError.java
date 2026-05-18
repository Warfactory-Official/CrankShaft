package dev.engine_room.flywheel.backend.gl.error;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.function.Supplier;

public enum GlError {
    INVALID_ENUM(GL11.GL_INVALID_ENUM),
    INVALID_VALUE(GL11.GL_INVALID_VALUE),
    INVALID_OPERATION(GL11.GL_INVALID_OPERATION),
    INVALID_FRAMEBUFFER_OPERATION(GL30.GL_INVALID_FRAMEBUFFER_OPERATION),
    OUT_OF_MEMORY(GL11.GL_OUT_OF_MEMORY),
    STACK_UNDERFLOW(GL11.GL_STACK_UNDERFLOW),
    STACK_OVERFLOW(GL11.GL_STACK_OVERFLOW),
    ;

    private static final Int2ObjectMap<GlError> errorLookup = new Int2ObjectArrayMap<>();

    static {
        errorLookup.defaultReturnValue(null);
        for (GlError value : values()) {
            errorLookup.put(value.glEnum, value);
        }
    }

    final int glEnum;

    GlError(int glEnum) {
        this.glEnum = glEnum;
    }

    public static GlError poll() {
        return errorLookup.get(GL11.glGetError());
    }

    public static void pollAndThrow(Supplier<String> context) {
    }
}
