package dev.engine_room.flywheel.impl.task;

import dev.engine_room.flywheel.impl.FlwImpl;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

public final class ForkJoinPoolTaskExecutor implements TaskExecutorImpl {
    private static final int SPIN_ITERATIONS = 1024;
    private static final long QUIESCE_TIMEOUT_SECONDS = 60L;

    private final ForkJoinPool pool;
    // The single sync waiter (the render thread; syncUntil is never called concurrently). Signaled park: raise
    // sites call wakeSync() so the waiter wakes AT the flag raise -- the predecessors both overslept on Windows.
    @Nullable
    private volatile Thread syncWaiter;

    public ForkJoinPoolTaskExecutor(ForkJoinPool pool) {
        this.pool = pool;
    }

    static void crashClient(String label, Throwable throwable) {
        FlwImpl.LOGGER.fatal(label, throwable);
        Minecraft.getInstance().delayCrash(CrashReport.forThrowable(throwable, label));
    }

    @Override
    public int threadCount() {
        return Math.max(1, pool.getParallelism());
    }

    @Override
    public ForkJoinPool forkJoinPool() {
        return pool;
    }

    @Override
    public void execute(Runnable command) {
        pool.execute(command);
    }

    @Override
    public boolean syncUntil(BooleanSupplier cond) {
        int spins = 0;
        while (spins < SPIN_ITERATIONS) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.onSpinWait();
            spins++;
        }
        syncWaiter = Thread.currentThread();
        try {
            while (!cond.getAsBoolean()) {
                if (pool.isQuiescent()) {
                    return cond.getAsBoolean();
                }
                LockSupport.parkNanos(1_000_000L);
            }
            return true;
        } finally {
            syncWaiter = null;
        }
    }

    @Override
    public void wakeSync() {
        Thread waiter = syncWaiter;
        if (waiter != null) {
            LockSupport.unpark(waiter);
        }
    }

    @Override
    public boolean syncWhile(BooleanSupplier cond) {
        return syncUntil(() -> !cond.getAsBoolean());
    }

    @Override
    public void syncPoint() {
        pool.awaitQuiescence(QUIESCE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void recordFailure(Throwable throwable) {
        crashClient("Flywheel worker task failed", throwable);
    }
}
