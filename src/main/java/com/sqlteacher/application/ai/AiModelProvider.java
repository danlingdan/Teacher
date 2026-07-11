package com.sqlteacher.application.ai;

import com.sqlteacher.application.ai.AiCompletionRequest;
import com.sqlteacher.application.ai.AiCompletionResult;

public interface AiModelProvider {
    AiCompletionResult complete(AiCompletionRequest request);
}