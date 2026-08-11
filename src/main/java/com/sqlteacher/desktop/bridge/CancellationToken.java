package com.sqlteacher.desktop.bridge;

@FunctionalInterface
public interface CancellationToken {
    boolean cancelled();

    default void throwIfCancelled() {
        if (cancelled()) throw new LocalAppCancelledException();
    }
}
