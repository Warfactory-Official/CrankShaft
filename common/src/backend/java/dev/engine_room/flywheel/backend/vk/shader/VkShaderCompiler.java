package dev.engine_room.flywheel.backend.vk.shader;

import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.vk.VkContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class VkShaderCompiler {
    public static final int KIND_VERTEX = Shaderc.shaderc_vertex_shader;
    public static final int KIND_FRAGMENT = Shaderc.shaderc_fragment_shader;
    public static final int KIND_COMPUTE = Shaderc.shaderc_compute_shader;

    private static final int TARGET_ENV_VULKAN = 0;
    private static final int VULKAN_1_2 = 4202496;
    private static final Map<ShaderKey, CompletableFuture<ByteBuffer>> SPIRV = new ConcurrentHashMap<>();
    private static final AtomicInteger compiledStages = new AtomicInteger();
    private static final AtomicInteger reusedStages = new AtomicInteger();
    private static final AtomicInteger activeCompilations = new AtomicInteger();
    private static final AtomicInteger peakCompilations = new AtomicInteger();
    private static long compiler;
    private static long options;
    private static volatile boolean namesUnavailable;

    private VkShaderCompiler() {
    }

    public static long compileModule(String name, String glsl, int shadercKind) {
        long start = System.nanoTime();
        ByteBuffer spirv = spirv(name, glsl, shadercKind);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                                                                    .sType$Default()
                                                                    .pCode(spirv);
            LongBuffer pModule = stack.callocLong(1);
            int result = VK12.vkCreateShaderModule(VkContext.vkDevice(), info, null, pModule);
            if (result != VK12.VK_SUCCESS) {
                throw new IllegalStateException("Vulkan error " + result + " creating shader module " + name);
            }
            FlwBackend.LOGGER.info("[vk] created shader module {} in {}ms", name,
                    (System.nanoTime() - start) / 1_000_000);
            nameModule(pModule.get(0), name);
            return pModule.get(0);
        }
    }

    public static void destroyModule(long module) {
        if (module != 0L) {
            VK12.vkDestroyShaderModule(VkContext.vkDevice(), module, null);
        }
    }

    private static void nameModule(long module, String name) {
        if (namesUnavailable) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkDebugUtilsObjectNameInfoEXT info = org.lwjgl.vulkan.VkDebugUtilsObjectNameInfoEXT.calloc(
                                                                             stack)
                                                                                                                .sType$Default()
                                                                                                                .objectType(
                                                                                                                        VK12.VK_OBJECT_TYPE_SHADER_MODULE)
                                                                                                                .objectHandle(
                                                                                                                        module)
                                                                                                                .pObjectName(
                                                                                                                        stack.UTF8(
                                                                                                                                name));
            org.lwjgl.vulkan.EXTDebugUtils.vkSetDebugUtilsObjectNameEXT(VkContext.vkDevice(), info);
        } catch (Throwable t) {
            namesUnavailable = true;
        }
    }

    public static ByteBuffer compileToSpirv(String name, String glsl, int shadercKind) {
        ByteBuffer shared = spirv(name, glsl, shadercKind);
        ByteBuffer copy = MemoryUtil.memAlloc(shared.remaining());
        MemoryUtil.memCopy(shared, copy);
        return copy;
    }

    private static ByteBuffer spirv(String name, String glsl, int kind) {
        ensureInit();
        ShaderKey key = new ShaderKey(glsl, kind);
        CompletableFuture<ByteBuffer> cached = SPIRV.get(key);
        if (cached == null) {
            // computeIfAbsent holds its bin lock across the compile => unrelated keys stall.
            CompletableFuture<ByteBuffer> pending = new CompletableFuture<>();
            cached = SPIRV.putIfAbsent(key, pending);
            if (cached == null) {
                try {
                    pending.complete(compileUncached(name, glsl, kind));
                } catch (RuntimeException | Error failure) {
                    SPIRV.remove(key, pending);
                    pending.completeExceptionally(failure);
                    throw failure;
                }
                return pending.join().asReadOnlyBuffer();
            }
        }
        reusedStages.incrementAndGet();
        return cached.join().asReadOnlyBuffer();
    }

    private static ByteBuffer compileUncached(String name, String glsl, int shadercKind) {
        // shaderc_compile_into_spv explicitly permits concurrent calls with a shared const compiler/options.
        int concurrent = activeCompilations.incrementAndGet();
        peakCompilations.accumulateAndGet(concurrent, Math::max);
        long result = Shaderc.shaderc_compile_into_spv(compiler, glsl, shadercKind, name, "main", options);
        try {
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                throw new IllegalStateException(
                        "Failed to compile " + name + ": " + Shaderc.shaderc_result_get_error_message(result));
            }
            ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
            ByteBuffer copy = MemoryUtil.memAlloc(spirv.remaining());
            MemoryUtil.memCopy(spirv, copy);
            compiledStages.incrementAndGet();
            return copy;
        } finally {
            Shaderc.shaderc_result_release(result);
            activeCompilations.decrementAndGet();
        }
    }

    public static int compiledStages() {
        return compiledStages.get();
    }

    public static int reusedStages() {
        return reusedStages.get();
    }

    public static int peakCompilations() {
        return peakCompilations.get();
    }

    public static void resetPeakCompilations() {
        peakCompilations.set(0);
    }

    public static long cachedBytes() {
        return SPIRV.values().stream().mapToLong(spirv -> spirv.join().remaining()).sum();
    }

    /**
     * Device shutdown, after every compilation job has joined.
     */
    public static void shutdown() {
        SPIRV.values().forEach(spirv -> MemoryUtil.memFree(spirv.join()));
        SPIRV.clear();
        if (compiler != 0L) {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
            compiler = 0L;
            options = 0L;
        }
    }

    private static synchronized void ensureInit() {
        if (compiler != 0L) {
            return;
        }
        compiler = Shaderc.shaderc_compiler_initialize();
        options = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(options, TARGET_ENV_VULKAN, VULKAN_1_2);
        if (Boolean.getBoolean("flywheel.vkShaderDebug")) {
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_zero);
            Shaderc.shaderc_compile_options_set_generate_debug_info(options);
        } else {
            Shaderc.shaderc_compile_options_set_optimization_level(options,
                    Shaderc.shaderc_optimization_level_performance);
        }
    }

    private record ShaderKey(String source, int kind) {
    }
}
