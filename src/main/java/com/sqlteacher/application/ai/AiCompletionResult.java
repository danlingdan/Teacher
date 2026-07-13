package com.sqlteacher.application.ai;

import java.util.Objects;

public final class AiCompletionResult {
    private final boolean success;
    private final String content;
    private final String errorMessage;
    private final String model;

    private AiCompletionResult(boolean success, String content, String errorMessage, String model) {
        this.success = success;
        this.content = content;
        this.errorMessage = errorMessage;
        this.model = model;
    }

    public static AiCompletionResult success(String content, String model) {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(model, "model must not be null");
        return new AiCompletionResult(true, content, null, model);
    }

    public static AiCompletionResult failure(String errorMessage, String model) {
        Objects.requireNonNull(errorMessage, "errorMessage must not be null");
        Objects.requireNonNull(model, "model must not be null");
        return new AiCompletionResult(false, null, errorMessage, model);
    }

    public boolean success() {
        return success;
    }

    public String content() {
        return content;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public String model() {
        return model;
    }
}