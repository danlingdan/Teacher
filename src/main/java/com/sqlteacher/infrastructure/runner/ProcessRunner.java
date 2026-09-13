package com.sqlteacher.infrastructure.runner;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Shared process lifecycle handling for code runners: a cancellable 50 ms polling wait and
 * deterministic tree destruction. Safety-relevant — the wait interval and the destruction
 * order (descendants first, then the process, each escalating from destroy to
 * destroyForcibly) must stay identical for every runner.
 */
final class ProcessRunner {

    private static final long POLL_INTERVAL_MS = 50;

    private ProcessRunner() { }

    /**
     * Waits for the process to exit while re-checking {@code abort} every 50 ms. When
     * {@code abort} fires, {@code onAbort} runs first (it must stop or destroy the process
     * tree) and the loop breaks; the blocking {@code waitFor()} exit code is then returned.
     *
     * @throws InterruptedException when the waiting thread is interrupted (the interrupt flag
     *                              is re-set by {@code waitFor} before the exception propagates)
     */
    static int awaitExit(Process process, BooleanSupplier abort, Runnable onAbort)
        throws InterruptedException {
        while (!process.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
            if (abort.getAsBoolean()) {
                onAbort.run();
                break;
            }
        }
        return process.waitFor();
    }

    /** Destroys the process tree: descendants first, then the process, force-killing survivors. */
    static void destroyTree(Process process) {
        process.descendants().forEach(handle -> {
            handle.destroy();
            if (handle.isAlive()) handle.destroyForcibly();
        });
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
    }
}
