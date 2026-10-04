package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.Destroyable;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkPipelineCaches;
import dev.engine_room.flywheel.backend.vk.descriptor.VkBindlessTable;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderCompiler;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loading-only Vulkan compilation. Each job exclusively owns its pipeline family or cache; the render thread
 * joins every job before any cache is consumed by drawing. Worker cleanup is transferred to the render thread.
 */
public final class VkCompilationTasks {
    private static volatile @Nullable Queue<Destroyable> cleanup;

    private VkCompilationTasks() {
    }

    public static boolean deferCleanup(Destroyable resource) {
        Queue<Destroyable> pending = cleanup;
        if (pending == null || RenderSystem.isOnRenderThread()) return false;
        pending.add(resource);
        return true;
    }

    public static void run(List<Runnable> jobs) {
        RenderSystem.assertOnRenderThread();
        VkPipelineCaches.initialize();
        if (VkCaps.BINDLESS_TEXTURES_NEGOTIATED) VkBindlessTable.setLayoutHandle();
        int threads = Math.min(jobs.size(), Runtime.getRuntime().availableProcessors());
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Queue<Destroyable> pending = new ConcurrentLinkedQueue<>();
        cleanup = pending;
        int stagesBefore = VkShaderCompiler.compiledStages();
        int reuseBefore = VkShaderCompiler.reusedStages();
        VkShaderCompiler.resetPeakCompilations();
        long start = System.nanoTime();
        Throwable failure = null;
        try (ExecutorService executor = Executors.newFixedThreadPool(threads,
                Thread.ofPlatform().name("CrankShaft-Vulkan-Compiler-", 0).factory())) {
            List<? extends Future<?>> futures = jobs.stream().map(job -> executor.submit(() -> {
                int current = active.incrementAndGet();
                peak.accumulateAndGet(current, Math::max);
                try {
                    job.run();
                } finally {
                    active.decrementAndGet();
                }
            })).toList();
            // Join all jobs even when one fails: no worker can outlive a reload, cache destruction or the device.
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    if (failure == null) failure = e.getCause();
                } catch (InterruptedException e) {
                    if (failure == null) failure = e;
                }
            }
        } finally {
            cleanup = null;
            pending.forEach(Destroyable::destroy);
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure != null) throw new RuntimeException("Vulkan warmup interrupted", failure);
        FlwBackend.LOGGER.info(
                "Vulkan shader warmup: {} jobs, {} workers peak, {} shaderc peak, {} compiled stages, {} reused stages, {} cached SPIR-V bytes, {}ms",
                jobs.size(), peak.get(), VkShaderCompiler.peakCompilations(),
                VkShaderCompiler.compiledStages() - stagesBefore,
                VkShaderCompiler.reusedStages() - reuseBefore, VkShaderCompiler.cachedBytes(),
                (System.nanoTime() - start) / 1_000_000);
    }
}
