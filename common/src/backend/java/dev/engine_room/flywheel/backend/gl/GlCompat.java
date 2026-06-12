package dev.engine_room.flywheel.backend.gl;

import dev.engine_room.flywheel.backend.FlwBackend;
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
            // This happens with vulkanmod installed.
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
    public static final boolean SUPPORTS_INSTANCING = isInstancingSupported();
    public static final boolean SUPPORTS_INDIRECT = isIndirectSupported();
    @Nullable
    public static final String TERRAIN_UNSUPPORTED_REASON = terrainUnsupportedReason();
    public static final boolean SUPPORTS_TERRAIN = TERRAIN_UNSUPPORTED_REASON == null;
    public static final boolean SUPPORTS_TERRAIN_MESH = isMeshShaderSupported();
    public static final boolean SUPPORTS_BINDLESS_TEXTURES = isBindlessTextureSupported();
    public static final boolean SUPPORTS_DEBUG_GROUP = CAPABILITIES != null && CAPABILITIES.glPushDebugGroup != MemoryUtil.NULL;
    public static final boolean SUPPORTS_TIMER_QUERY = CAPABILITIES != null && CAPABILITIES.glQueryCounter != MemoryUtil.NULL;
    public static final boolean SUPPORTS_FRAGMENT_INTERLOCK = CAPABILITIES != null && CAPABILITIES.GL_ARB_fragment_shader_interlock;
    public static final boolean SUPPORTS_TEXTURE_VIEW = CAPABILITIES != null
            && CAPABILITIES.glTextureView != MemoryUtil.NULL
            && CAPABILITIES.glTexStorage3D != MemoryUtil.NULL;
    private static final boolean PARAMETER_BUFFER_CORE = CAPABILITIES != null
            && CAPABILITIES.glMultiDrawElementsIndirectCount != MemoryUtil.NULL;
    // Debugger opt-out (flip to true + rebuild): Nsight C++ capture cannot record bindless residency,
    // so this forces the classic per-batch bind path.
    private static final boolean DISABLE_BINDLESS = false;

    private GlCompat() {
    }

    public static void init() {
    }

    public static void pushDebugGroup(String name) {
        GlGpuTimer.push(name);
        if (SUPPORTS_DEBUG_GROUP) {
            KHRDebug.glPushDebugGroup(KHRDebug.GL_DEBUG_SOURCE_APPLICATION, 0, name);
        }
    }

    public static void popDebugGroup() {
        GlGpuTimer.pop();
        if (SUPPORTS_DEBUG_GROUP) {
            KHRDebug.glPopDebugGroup();
        }
    }

    public static int getComputeGroupCount(int invocations) {
        return ceilingDiv(invocations, SUBGROUP_SIZE);
    }

    private static int ceilingDiv(int x, int y) {
        return (x + y - 1) / y;
    }

    /**
     * Modified from:
     * <br> <a href="https://github.com/grondag/canvas/commit/820bf754092ccaf8d0c169620c2ff575722d7d96">canvas</a>
     *
     * <p>Identical in function to {@link GL20C#glShaderSource(int, CharSequence)} but
     * passes a null pointer for string length to force the driver to rely on the null
     * terminator for string length.  This is a workaround for an apparent flaw with some
     * AMD drivers that don't receive or interpret the length correctly, resulting in
     * an access violation when the driver tries to read past the string memory.
     *
     * <p>Hat tip to fewizz for the find and the fix.
     */
    public static void safeShaderSource(int glId, CharSequence source) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var sourceBuffer = MemoryUtil.memUTF8(source, true);
            final PointerBuffer pointers = stack.mallocPointer(1);
            pointers.put(sourceBuffer);
            GL20C.nglShaderSource(glId, 1, pointers.address0(), 0);
            MemoryUtil.memFree(sourceBuffer);
        }
    }

    private static Driver readVendorString() {
        if (CAPABILITIES == null) {
            return Driver.UNKNOWN;
        }

        // The vendor string I got was "ATI Technologies Inc."
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

        // Try to guess.
        // Newer (RDNA) AMD cards have 32 threads in a wavefront, older ones have 64.
        // I assume the newer drivers will implement the above extension, so 64 is a
        // reasonable guess for AMD hardware. In the worst case we'll just spread
        // load across multiple SIMDs
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
        boolean result = CAPABILITIES.GL_ARB_compute_shader
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
        FlwBackend.LOGGER.info(
                "[indirect] SUPPORTS_INDIRECT={} (OpenGL46={}, compute_shader={}, dsa={}, gpu_shader5={}, multi_bind={}, mdi={}, draw_params={}, ssbo={}, 420pack={}, vab={}, image_load_store={}, image_size={})",
                result, CAPABILITIES.OpenGL46, CAPABILITIES.GL_ARB_compute_shader,
                CAPABILITIES.GL_ARB_direct_state_access,
                CAPABILITIES.GL_ARB_gpu_shader5, CAPABILITIES.GL_ARB_multi_bind,
                CAPABILITIES.GL_ARB_multi_draw_indirect,
                CAPABILITIES.GL_ARB_shader_draw_parameters, CAPABILITIES.GL_ARB_shader_storage_buffer_object,
                CAPABILITIES.GL_ARB_shading_language_420pack, CAPABILITIES.GL_ARB_vertex_attrib_binding,
                CAPABILITIES.GL_ARB_shader_image_load_store, CAPABILITIES.GL_ARB_shader_image_size);
        return result;
    }

    private static boolean isMeshShaderSupported() {
        if (CAPABILITIES == null || DRIVER != Driver.NVIDIA) {
            return false;
        }
        // Fn-pointer gate, never the extension flag -- the 26.2 forward-compat context hides it.
        // NV_shader_buffer_load predates NV_mesh_shader, so every mesh-capable GPU has the
        // resident-buffer fn-pointers too.
        boolean supported = CAPABILITIES.glDrawMeshTasksNV != MemoryUtil.NULL
                && CAPABILITIES.glMakeBufferResidentNV != MemoryUtil.NULL
                && CAPABILITIES.glGetBufferParameterui64vNV != MemoryUtil.NULL;
        FlwBackend.LOGGER.info(
                "[mesh] terrain mesh-shader support: {} (glDrawMeshTasksNV={}, glMultiDrawMeshTasksIndirectNV={}, glMakeBufferResidentNV={}, glGetBufferParameterui64vNV={}, GL_NV_mesh_shader extension-flag={})",
                supported, CAPABILITIES.glDrawMeshTasksNV, CAPABILITIES.glMultiDrawMeshTasksIndirectNV,
                CAPABILITIES.glMakeBufferResidentNV, CAPABILITIES.glGetBufferParameterui64vNV,
                CAPABILITIES.GL_NV_mesh_shader);
        return supported;
    }

    private static boolean isBindlessTextureSupported() {
        if (CAPABILITIES == null) {
            return false;
        }
        if (DISABLE_BINDLESS) {
            return false;
        }
        return CAPABILITIES.glGetTextureSamplerHandleARB != MemoryUtil.NULL
                && CAPABILITIES.glMakeTextureHandleResidentARB != MemoryUtil.NULL;
    }

    @Nullable
    private static String terrainUnsupportedReason() {
        if (CAPABILITIES == null) {
            return "no GL capabilities";
        }
        if (!(CAPABILITIES.OpenGL43 || CAPABILITIES.GL_ARB_compute_shader)) {
            return "compute shaders are unavailable";
        }
        if (!(CAPABILITIES.OpenGL43 || CAPABILITIES.GL_ARB_shader_storage_buffer_object)) {
            return "shader storage buffers are unavailable";
        }
        if (!(CAPABILITIES.OpenGL43 || CAPABILITIES.GL_ARB_multi_draw_indirect)) {
            return "multi draw indirect is unavailable";
        }
        if (CAPABILITIES.glMultiDrawElementsIndirectCount == MemoryUtil.NULL
                && CAPABILITIES.glMultiDrawElementsIndirectCountARB == MemoryUtil.NULL) {
            return "glMultiDrawElementsIndirectCount is unavailable (need OpenGL 4.6 or GL_ARB_indirect_parameters)";
        }
        return null;
    }

    public static void multiDrawElementsIndirectCount(int mode, int type, long indirect, long drawCount,
                                                      int maxDrawCount, int stride) {
        if (PARAMETER_BUFFER_CORE) {
            GL46.glMultiDrawElementsIndirectCount(mode, type, indirect, drawCount, maxDrawCount, stride);
        } else {
            ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(mode, type, indirect, drawCount, maxDrawCount,
                    stride);
        }
    }

    private static GlslVersion maxGlslVersion() {
        if (CAPABILITIES == null) {
            return GlslVersion.V150;
        }

        var glslVersions = GlslVersion.values();
        // No need to test glsl 150 as that is guaranteed to be supported by MC.
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

        // Compile the simplest possible shader.
        var source = """
                #version %d
                void main() {}
                """.formatted(version.version);

        safeShaderSource(handle, source);
        GL20.glCompileShader(handle);

        boolean success = GL20.glGetShaderi(handle, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE;

        GL20.glDeleteShader(handle);

        return success;
    }

    /**
     * Get a non-null string from OpenGL, or "invalid" if no capabilities are available.
     */
    private static String safeGetString(int name) {
        if (CAPABILITIES == null) {
            return "invalid";
        }
        String str = GL11C.glGetString(name);
        return str == null ? "null" : str;
    }
}
