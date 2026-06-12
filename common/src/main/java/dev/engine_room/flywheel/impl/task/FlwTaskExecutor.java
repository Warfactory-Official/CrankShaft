package dev.engine_room.flywheel.impl.task;

import dev.engine_room.flywheel.impl.FlwConfig;
import dev.engine_room.flywheel.impl.FlwImpl;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class FlwTaskExecutor {
    private static final AtomicLazy INSTANCE = new AtomicLazy();

    private FlwTaskExecutor() {
    }

    /**
     * Get the global Flywheel thread pool.
     */
    public static TaskExecutorImpl get() {
        return INSTANCE.get();
    }

    private static ForkJoinPool newDedicatedPool(int parallelism) {
        AtomicInteger seq = new AtomicInteger();
        ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> {
            ForkJoinWorkerThread w = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            w.setName("Flywheel-Worker-" + seq.getAndIncrement());
            w.setContextClassLoader(FlwTaskExecutor.class.getClassLoader());
            return w;
        };
        Thread.UncaughtExceptionHandler handler = (t, e) ->
                ForkJoinPoolTaskExecutor.crashClient("Uncaught exception in Flywheel worker " + t.getName(), e);
        return new ForkJoinPool(parallelism, factory, handler, false);
    }

    private static class AtomicLazy {
        private final AtomicReference<AtomicLazy> factory = new AtomicReference<>();
        private final AtomicReference<TaskExecutorImpl> reference = new AtomicReference<>();

        public final TaskExecutorImpl get() {
            TaskExecutorImpl result;

            while ((result = reference.get()) == null) {
                if (factory.compareAndSet(null, this)) {
                    reference.set(initialize());
                }
            }

            return result;
        }

        protected TaskExecutorImpl initialize() {
            if (FlwConfig.INSTANCE.useCommonPool()) {
                FlwImpl.LOGGER.info("Flywheel task executor: JVM common pool (parallelism={})",
                        ForkJoinPool.commonPool().getParallelism());
                return new ForkJoinPoolTaskExecutor(ForkJoinPool.commonPool());
            }

            int parallelism = FlwConfig.INSTANCE.workerThreadCount();
            if (parallelism == 1) {
                FlwImpl.LOGGER.info("Flywheel task executor: serial (parallelism=1)");
                return SerialTaskExecutor.INSTANCE;
            }

            FlwImpl.LOGGER.info("Flywheel task executor: dedicated ForkJoinPool (parallelism={})", parallelism);
            return new ForkJoinPoolTaskExecutor(newDedicatedPool(parallelism));
        }
    }
}
