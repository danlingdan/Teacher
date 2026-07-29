package com.sqlteacher.application.ai;

public record AiTaskResult(boolean success, String content, String model, AiTaskErrorCode errorCode, String message) {
    public static AiTaskResult success(String content, String model) {
        return new AiTaskResult(true, content, model, null, "");
    }

    public static AiTaskResult failure(AiTaskErrorCode code, String message, String model) {
        return new AiTaskResult(false, "", model, code, message);
    }
}
