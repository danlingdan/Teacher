package com.sqlteacher.application.ai;

import java.util.List;

public record AiProviderProbeResult(boolean success, List<String> models, String message, AiTaskErrorCode errorCode) {
    public AiProviderProbeResult {
        models = models == null ? List.of() : List.copyOf(models);
        message = message == null ? "" : message;
    }
}
