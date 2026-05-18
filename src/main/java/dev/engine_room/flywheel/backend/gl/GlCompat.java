package dev.engine_room.flywheel.backend.gl;

import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class GlCompat {
    @Nullable
    public static final GLCapabilities CAPABILITIES;
    static {
        GLCapabilities caps;
        try {
            caps = GL.getCapabilities();
        } catch (IllegalStateException e) {
            FlwBackend.LOGGER.warn("Failed to get GL capabilities; default Flywheel backends will be disabled.");
            caps = null;
        }
        CAPABILITIES = caps;
    }

    public static final String GL_VENDOR_STRING = safeGetString(GL11C.GL_VENDOR);
    public static final String GL_RENDERER_STRING = safeGetString(GL11C.GL_RENDERER);
    public static final String GL_VERSION_STRING = safeGetString(GL11C.GL_VERSION);
    public static final String GL_SHADING_LANGUAGE_VERSION_STRING = safeGetString(GL20C.GL_SHADING_LANGUAGE_VERSION);

    public static final Driver DRIVER = readVendorString();
    public static final int SUBGROUP_SIZE = subgroupSize();
    public static final boolean ALLOW_DSA = true;
    public static final GlslVersion MAX_GLSL_VERSION = maxGlslVersion();

    public static final boolean SUPPORTS_DSA = ALLOW_DSA && isDsaSupported();

    public static final boolean SUPPORTS_INSTANCING = isInstancingSupported();
    public static final boolean SUPPORTS_INDIRECT = isIndirectSupported();

    private GlCompat() {
    }

    public static void init() {
    }

    public static int getComputeGroupCount(int invocations) {
        return ceilingDiv(invocations, SUBGROUP_SIZE);
    }

    private static int ceilingDiv(int x, int y) {
        return (x + y - 1) / y;
    }

    public static void safeShaderSource(int glId, CharSequence source) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var sourceBuffer = MemoryUtil.memUTF8(source, true);
            final PointerBuffer pointers = stack.mallocPointer(1);
            pointers.put(sourceBuffer);
            GL20C.nglShaderSource(glId, 1, pointers.address0(), 0);
            MemoryUtil.memFree(sourceBuffer);
        }
    }

    public static void safeMultiDrawElementsIndirect(GlProgram drawProgram, int mode, int type, int start, int end, long stride) {
        var count = end - start;
        long indirect = start * stride;

        if (GlCompat.DRIVER == Driver.INTEL) {
            for (int i = 0; i < count; i++) {
                drawProgram.setUInt("_flw_baseDraw", start + i);
                GL40.glDrawElementsIndirect(mode, type, indirect);
                indirect += stride;
            }
        } else {
            drawProgram.setUInt("_flw_baseDraw", start);
            GL43.glMultiDrawElementsIndirect(mode, type, indirect, count, (int) stride);
        }
    }

    private static Driver readVendorString() {
        if (CAPABILITIES == null) {
            return Driver.UNKNOWN;
        }

        if (GL_VENDOR_STRING.contains("ATI") || GL_VENDOR_STRING.contains("AMD")) {
            return Driver.AMD;
        } else if (GL_VENDOR_STRING.contains("NVIDIA")) {
            return Driver.NVIDIA;
        } else if (GL_VENDOR_STRING.contains("Intel")) {
            return Driver.INTEL;
        } else if (GL_VENDOR_STRING.contains("Mesa")) {
            return Driver.MESA;
        }

        return Driver.UNKNOWN;
    }

    private static int subgroupSize() {
        if (CAPABILITIES == null) {
            return 32;
        }
        if (CAPABILITIES.GL_KHR_shader_subgroup) {
            return GL11C.glGetInteger(KHRShaderSubgroup.GL_SUBGROUP_SIZE_KHR);
        }

        return DRIVER == Driver.AMD || DRIVER == Driver.MESA ? 64 : 32;
    }

    private static boolean isInstancingSupported() {
        if (CAPABILITIES == null) {
            return false;
        }
        if (CAPABILITIES.OpenGL33) {
            return true;
        }
        return CAPABILITIES.GL_ARB_shader_bit_encoding;
    }

    private static boolean isIndirectSupported() {
        if (CAPABILITIES == null) {
            return false;
        }
        if (CAPABILITIES.OpenGL46) {
            return true;
        }
        return CAPABILITIES.GL_ARB_compute_shader
                && CAPABILITIES.GL_ARB_direct_state_access
                && CAPABILITIES.GL_ARB_gpu_shader5
                && CAPABILITIES.GL_ARB_multi_bind
                && CAPABILITIES.GL_ARB_multi_draw_indirect
                && CAPABILITIES.GL_ARB_shader_draw_parameters
                && CAPABILITIES.GL_ARB_shader_storage_buffer_object
                && CAPABILITIES.GL_ARB_shading_language_420pack
                && CAPABILITIES.GL_ARB_vertex_attrib_binding
                && CAPABILITIES.GL_ARB_shader_image_load_store
                && CAPABILITIES.GL_ARB_shader_image_size;
    }

    private static boolean isDsaSupported() {
        if (CAPABILITIES == null) {
            return false;
        }

        return CAPABILITIES.GL_ARB_direct_state_access;
    }

    private static GlslVersion maxGlslVersion() {
        if (CAPABILITIES == null) {
            return GlslVersion.V150;
        }

        var glslVersions = GlslVersion.values();
        for (int i = glslVersions.length - 1; i > 0; i--) {
            var version = glslVersions[i];

            if (canCompileVersion(version)) {
                return version;
            }
        }

        return GlslVersion.V150;
    }

    private static boolean canCompileVersion(GlslVersion version) {
        int handle = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);

        var source = """
                #version %d
                void main() {}
                """.formatted(version.version);

        safeShaderSource(handle, source);
        GL20.glCompileShader(handle);

        boolean success = Compilation.compiledSuccessfully(handle);

        GL20.glDeleteShader(handle);

        return success;
    }

    private static String safeGetString(int name) {
        if (CAPABILITIES == null) {
            return "invalid";
        }
        String str = GL11C.glGetString(name);
        return str == null ? "null" : str;
    }
}
