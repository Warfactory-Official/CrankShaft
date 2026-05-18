package dev.engine_room.flywheel.impl.task;

import dev.engine_room.flywheel.impl.FlwImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.crash.CrashReport;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

public final class ForkJoinPoolTaskExecutor implements TaskExecutorImpl {
    private static final int SPIN_ITERATIONS = 1024;
    private static final long PARK_NANOS = 1_000L;
    private static final long QUIESCE_TIMEOUT_SECONDS = 60L;

    private final ForkJoinPool pool;

    public ForkJoinPoolTaskExecutor(ForkJoinPool pool) {
        this.pool = pool;
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
        // Quiescence is the bailout signal: if no work is pending, no future submit will flip
        // cond either. Reliable on the dedicated pool (we own it); best-effort on the common
        // pool, where unrelated JVM work can either hold quiescence off or settle ahead of us.
        int spins = 0;
        while (!cond.getAsBoolean()) {
            if (spins < SPIN_ITERATIONS) {
                Thread.onSpinWait();
                spins++;
            } else {
                if (pool.isQuiescent()) {
                    return cond.getAsBoolean();
                }
                LockSupport.parkNanos(PARK_NANOS);
            }
        }
        return true;
    }

    @Override
    public boolean syncWhile(BooleanSupplier cond) {
        // Mirror of syncUntil with the predicate inverted — see there for the quiescence rationale.
        int spins = 0;
        while (cond.getAsBoolean()) {
            if (spins < SPIN_ITERATIONS) {
                Thread.onSpinWait();
                spins++;
            } else {
                if (pool.isQuiescent()) {
                    return !cond.getAsBoolean();
                }
                LockSupport.parkNanos(PARK_NANOS);
            }
        }
        return true;
    }

    @Override
    public void syncPoint() {
        pool.awaitQuiescence(QUIESCE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void recordFailure(Throwable throwable) {
        crashClient("Flywheel worker task failed", throwable);
    }

    static void crashClient(String label, Throwable throwable) {
        FlwImpl.LOGGER.fatal(label, throwable);
        Minecraft.getMinecraft().crashed(CrashReport.makeCrashReport(throwable, label));
    }
}
