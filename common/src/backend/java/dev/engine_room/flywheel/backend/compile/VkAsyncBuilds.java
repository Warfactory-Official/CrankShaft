package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.Destroyable;
import dev.engine_room.flywheel.backend.vk.VkContext;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Draw-time Vulkan pipeline builds on worker threads, from sources and flags the render thread captured. The render
 * thread publishes completed builds at {@link #poll}, its frame boundary, and destroys what workers or releases left.
 * During a warm-up ({@link #warm}) builds run on the calling thread.
 */
public final class VkAsyncBuilds {
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(
            Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 1, 4),
            Thread.ofPlatform().daemon().name("CrankShaft-Vulkan-Builder-", 0).factory());
    private static final Queue<Destroyable> CLEANUP = new ConcurrentLinkedQueue<>();
    private static final List<Build<?>> PENDING = new ArrayList<>();
    private static volatile boolean warming;

    private VkAsyncBuilds() {
    }

    /**
     * Render thread; builds inside {@code action}, including its loading workers, complete before it returns.
     */
    public static void warm(Runnable action) {
        RenderSystem.assertOnRenderThread();
        boolean outer = !warming;
        warming = true;
        try {
            action.run();
        } finally {
            if (outer) warming = false;
        }
    }

    /**
     * A worker's Vulkan cleanup, destroyed on the render thread at the next {@link #poll}.
     */
    static void deferCleanup(Destroyable resource) {
        CLEANUP.add(resource);
    }

    /**
     * {@code build} runs on a worker and must read only what the caller captured; {@code destroy} disposes of a
     * superseded result on the render thread.
     */
    public static <T> Build<T> request(Supplier<T> build, Consumer<T> destroy) {
        Build<T> request = new Build<>(destroy);
        if (warming) {
            request.published = build.get();
            return request;
        }
        RenderSystem.assertOnRenderThread();
        request.future = CompletableFuture.supplyAsync(build, WORKERS);
        PENDING.add(request);
        return request;
    }

    /**
     * Render thread, before recording: publishes completed builds; a failure throws, after every completed build is
     * published.
     */
    public static void poll() {
        RenderSystem.assertOnRenderThread();
        for (Destroyable resource; (resource = CLEANUP.poll()) != null; ) VkContext.deferDestroy(resource);
        RuntimeException failure = null;
        for (int i = 0; i < PENDING.size(); ) {
            Build<?> build = PENDING.get(i);
            if (!build.future.isDone()) {
                i++;
                continue;
            }
            PENDING.remove(i);
            RuntimeException thrown = build.complete();
            if (failure == null) failure = thrown;
            else if (thrown != null) failure.addSuppressed(thrown);
        }
        if (failure != null) throw failure;
    }

    /**
     * Render thread: waits for every pending build before the caches, passes or device it uses go away; failures of
     * builds nothing awaits any more are dropped.
     */
    public static void join() {
        RenderSystem.assertOnRenderThread();
        for (Build<?> build : PENDING) {
            build.released = true;
            build.future.handle((_, _) -> null).join();
            build.complete();
        }
        PENDING.clear();
        for (Destroyable resource; (resource = CLEANUP.poll()) != null; ) VkContext.deferDestroy(resource);
    }

    public static final ProgramVersions.Tracker<Build<?>> TRACKER = new ProgramVersions.Tracker<>() {
        @Override
        public ProgramVersions.State state(Build<?> build) {
            return build.published != null ? ProgramVersions.State.PUBLISHED
                    : build.released ? ProgramVersions.State.ABSENT : ProgramVersions.State.PENDING;
        }

        @Override
        public void release(Build<?> build) {
            build.release();
        }
    };

    /**
     * Builds published as a unit; null slots are not members.
     */
    public static final ProgramVersions.Tracker<@Nullable Build<?>[]> GROUP = new ProgramVersions.Tracker<>() {
        @Override
        public ProgramVersions.State state(@Nullable Build<?>[] group) {
            ProgramVersions.State state = ProgramVersions.State.PUBLISHED;
            for (Build<?> build : group) {
                if (build == null) continue;
                ProgramVersions.State member = TRACKER.state(build);
                if (member == ProgramVersions.State.ABSENT) return member;
                if (member == ProgramVersions.State.PENDING) state = member;
            }
            return state;
        }

        @Override
        public void release(@Nullable Build<?>[] group) {
            for (Build<?> build : group) if (build != null) build.release();
        }
    };

    public static final class Build<T> {
        private final Consumer<T> destroy;
        private @Nullable CompletableFuture<T> future;
        private @Nullable T published;
        private boolean released;

        private Build(Consumer<T> destroy) {
            this.destroy = destroy;
        }

        /**
         * The built result once published.
         */
        public T result() {
            return published;
        }

        private @Nullable RuntimeException complete() {
            try {
                T value = future.join();
                if (released) destroy.accept(value);
                else published = value;
                return null;
            } catch (CompletionException wrapped) {
                if (wrapped.getCause() instanceof Error error) throw error;
                boolean awaited = !released;
                released = true;
                if (!awaited) return null;
                return wrapped.getCause() instanceof RuntimeException failure ? failure : wrapped;
            }
        }

        private void release() {
            released = true;
            if (published != null) {
                destroy.accept(published);
                published = null;
            }
        }
    }
}
