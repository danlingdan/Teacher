package com.sqlteacher.application.runner;

@FunctionalInterface
public interface RunnerCancellation {
    RunnerCancellation NONE = () -> false;

    boolean isCancelled();
}
