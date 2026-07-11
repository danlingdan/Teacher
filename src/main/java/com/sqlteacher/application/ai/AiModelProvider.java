package com.sqlteacher.application.ai;

public interface AiModelProvider {
    AiCompletionResult complete(AiCompletionRequest request);
}