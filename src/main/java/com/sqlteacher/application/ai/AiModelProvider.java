package com.sqlteacher.application.ai;

@FunctionalInterface
public interface AiModelProvider {
    AiCompletionResult complete(AiCompletionRequest request);

    default String preferredModel() { return ""; }
}
