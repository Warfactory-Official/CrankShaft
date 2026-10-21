package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

final class GuestSamplerUnits {
    private final boolean[] samplerUnits;
    private final Object2IntOpenHashMap<String> privateUnits = new Object2IntOpenHashMap<>();

    GuestSamplerUnits() {
        privateUnits.defaultReturnValue(-1);
        samplerUnits = new boolean[GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)];
    }

    static void bindTexture(int unit, int target, int texture) {
        if (unit < 0) return;
        GlStateTracker.hostActiveTexture(GL13.GL_TEXTURE0 + unit);
        GlStateTracker.hostBindTexture(target, texture);
    }

    private static boolean sampler(int type) {
        return type >= GL20.GL_SAMPLER_1D && type <= GL20.GL_SAMPLER_2D_SHADOW
                || type >= GL31.GL_SAMPLER_2D_RECT && type <= GL31.GL_SAMPLER_2D_RECT_SHADOW
                || type >= GL30.GL_SAMPLER_1D_ARRAY && type <= GL30.GL_SAMPLER_CUBE_SHADOW
                || type >= GL30.GL_INT_SAMPLER_1D && type <= GL31.GL_UNSIGNED_INT_SAMPLER_BUFFER
                || type >= 0x9108 && type <= 0x910D || type >= 0x900C && type <= 0x900F;
    }

    void capture(int program) {
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1);
            var type = stack.mallocInt(1);
            var count = GL20.glGetProgrami(program, GL20.GL_ACTIVE_UNIFORMS);
            var nameSize = GL20.glGetProgrami(program, GL20.GL_ACTIVE_UNIFORM_MAX_LENGTH);
            for (var index = 0; index < count; index++) {
                var name = GL20.glGetActiveUniform(program, index, nameSize, size, type);
                if (!sampler(type.get(0))) continue;
                if (name.startsWith("_flw_") || name.equals("flw_overlayTex") || name.equals(
                        "flw_lightTex") || name.equals("flw_diffuseTex")) continue;
                for (var element = 0; element < size.get(0); element++) {
                    var elementName = size.get(0) == 1 ? name : name.replace("[0]", "[" + element + "]");
                    var location = GL20.glGetUniformLocation(program, elementName);
                    if (location >= 0) {
                        var unit = GL20.glGetUniformi(program, location);
                        if (privateUnits.containsValue(unit))
                            throw new IllegalStateException("Shader host sampler collides with guest unit: " + name);
                        samplerUnits[unit] = true;
                    }
                }
            }
        }
    }

    int instanceSampler() {
        return reserve("instances");
    }

    int reserve(String name) {
        var unit = privateUnits.getInt(name);
        if (unit < 0) {
            unit = allocate();
            privateUnits.put(name, unit);
        }
        return unit;
    }

    private int allocate() {
        for (var unit = samplerUnits.length - 1; unit >= 0; unit--) {
            if (!samplerUnits[unit]) {
                samplerUnits[unit] = true;
                return unit;
            }
        }
        throw new IllegalStateException("Shader host program leaves no instance sampler unit");
    }

    int[] guestSamplers(int count) {
        var units = new int[count];
        for (var index = 0; index < count; index++) units[index] = reserve("oit" + index);
        return units;
    }
}
