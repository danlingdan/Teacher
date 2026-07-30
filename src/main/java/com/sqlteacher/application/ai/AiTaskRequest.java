package com.sqlteacher.application.ai;

import java.util.Objects;

public record AiTaskRequest(AiTaskType type, String model, String prompt, String promptVersion, AiContextPreview preview) {
    public AiTaskRequest {
        type = Objects.requireNonNull(type, "type must not be null");
        model = model == null ? "" : model.strip();
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt must not be blank");
        promptVersion = promptVersion == null ? "unversioned" : promptVersion.strip();
        preview = Objects.requireNonNull(preview, "preview must not be null");
    }
}
