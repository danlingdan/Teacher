package com.sqlteacher.desktop.mock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs mock (and real) backend calls off the JavaFX application thread.
 *
 * <p>This utility is intentionally free of any JavaFX dependency so it can be exercised in
 * plain unit tests. The UI thread is represented by a generic {@link Executor}; JavaFX callers
 * can pass a {@code Platform.runLater}-based executor for {@code uiExecutor} without this class
 * having to import JavaFX.
 */
public final class AsyncMockInvoker implements AutoCloseable {

    private final ExecutorService executor;

    public AsyncMockInvoker() {
        this(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mock-async-invoker");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public AsyncMockInvoker(ExecutorService executor) {
        this.executor = executor;
    }

    /**
     * Executes {@code backendCall} on the background executor and returns a future that
     * completes with its result (or completes exceptionally with the unwrapped cause).
     */
    public <T> CompletableFuture<T> invokeAsync(Supplier<T> backendCall) {
        return CompletableFuture.supplyAsync(backendCall, executor);
    }

    /**
     * Executes {@code backendCall} on the background executor, then delivers the outcome on the
     * supplied {@code uiExecutor}. Exactly one of {@code onSuccess} / {@code onError} is invoked.
     */
    public <T> void invoke(
        Supplier<T> backendCall,
        Executor uiExecutor,
        Consumer<T> onSuccess,
        Consumer<Throwable> onError
    ) {
        CompletableFuture.supplyAsync(backendCall, executor)
            .whenComplete((result, error) -> uiExecutor.execute(() -> {
                if (error != null) {
                    onError.accept(unwrap(error));
                } else {
                    onSuccess.accept(result);
                }
            }));
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
