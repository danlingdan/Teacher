package com.sqlteacher.application.mock;

import com.sqlteacher.application.ai.AiAvailability;
import com.sqlteacher.application.ai.AiStatus;
import com.sqlteacher.application.ai.AiStatusService;

public final class MockAiStatusService implements AiStatusService {
    @Override
    public AiStatus checkStatus() {
        return new AiStatus(
            AiAvailability.AVAILABLE,
            "mock-ollama",
            "mock://ollama",
            1,
            "Mock AI service is ready"
        );
    }
}
