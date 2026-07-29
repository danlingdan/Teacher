package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class DefaultAiTaskService implements AiTaskService {
    private final AiModelProvider provider;
    private final AiUsagePolicy policy;
    private final AiTaskHistoryService history;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public DefaultAiTaskService(AiModelProvider provider, AiUsagePolicy policy, AiTaskHistoryService history) {
        this.provider = Objects.requireNonNull(provider);
        this.policy = Objects.requireNonNull(policy);
        this.history = Objects.requireNonNull(history);
    }

    @Override public AiTaskResult execute(AiTaskRequest request) {
        Objects.requireNonNull(request);
        long started = System.nanoTime();
        String model = request.model().isBlank() ? provider.preferredModel() : request.model();
        AiTaskResult outcome;
        if (request.prompt().length() > policy.maxInputCharacters()) {
            outcome = AiTaskResult.failure(AiTaskErrorCode.RESPONSE_TOO_LARGE, "发送内容超过设备限制，请缩小上下文。", model);
        } else if (history.requestsToday() >= policy.dailyRequestLimit()) {
            outcome = AiTaskResult.failure(AiTaskErrorCode.RATE_LIMITED, "已达到设备今日 AI 调用提醒上限。", model);
        } else if (model == null || model.isBlank()) {
            outcome = AiTaskResult.failure(AiTaskErrorCode.MODEL_NOT_FOUND, "没有可用模型，请先在 AI 助手中选择模型。", "");
        } else {
            outcome = callWithOneBoundedRetry(request, model);
        }
        long duration = (System.nanoTime() - started) / 1_000_000;
        try {
            history.record(new AiTaskHistoryEntry(UUID.randomUUID().toString(), Instant.now(), request.type(),
                outcome.model(), outcome.success(), outcome.success() ? "SUCCESS" : outcome.errorCode().name(),
                duration, request.promptVersion(), false, ""));
        } catch (RuntimeException ignored) {
            // Audit persistence must not turn a successfully generated draft into a failed task.
        }
        return outcome;
    }

    @Override public String preferredModel() { return provider.preferredModel(); }

    private AiTaskResult callWithOneBoundedRetry(AiTaskRequest request, String model) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (Thread.currentThread().isInterrupted())
                return AiTaskResult.failure(AiTaskErrorCode.CANCELLED, "AI 请求已取消。", model);
            try {
                AiCompletionResult result = provider.complete(new AiCompletionRequest(model, request.prompt(), policy.timeout()));
                if (result.success()) {
                    if (result.content() == null || result.content().isBlank())
                        return AiTaskResult.failure(AiTaskErrorCode.MALFORMED_OUTPUT, "AI 返回了空内容。", result.model());
                    if (result.content().length() > policy.maxOutputCharacters())
                        return AiTaskResult.failure(AiTaskErrorCode.RESPONSE_TOO_LARGE, "AI 响应超过设备限制，已拒绝使用。", result.model());
                    if (request.type() != AiTaskType.FEEDBACK_DRAFT) {
                        try {
                            if (!mapper.readTree(result.content()).isObject())
                                return AiTaskResult.failure(AiTaskErrorCode.MALFORMED_OUTPUT, "AI 未返回结构化 JSON 对象。", result.model());
                        } catch (Exception error) {
                            return AiTaskResult.failure(AiTaskErrorCode.MALFORMED_OUTPUT, "AI 返回内容无法解析为 JSON。", result.model());
                        }
                    }
                    return AiTaskResult.success(result.content(), result.model());
                }
                AiTaskErrorCode code = mapFailure(result.errorMessage());
                if (attempt == 0 && (code == AiTaskErrorCode.TIMED_OUT || code == AiTaskErrorCode.PROVIDER_UNAVAILABLE)) continue;
                return AiTaskResult.failure(code, safeMessage(result.errorMessage()), result.model());
            } catch (RuntimeException error) {
                if (attempt == 0) continue;
                return AiTaskResult.failure(AiTaskErrorCode.PROVIDER_UNAVAILABLE,
                    "AI Provider 暂不可用，请检查连接或切换 Provider。", model);
            }
        }
        return AiTaskResult.failure(AiTaskErrorCode.PROVIDER_UNAVAILABLE, "AI Provider 暂不可用。", model);
    }

    private static AiTaskErrorCode mapFailure(String message) {
        String normalized = message == null ? "" : message.toLowerCase();
        if (normalized.contains("401") || normalized.contains("403") || normalized.contains("auth")) return AiTaskErrorCode.AUTHENTICATION_FAILED;
        if (normalized.contains("429") || normalized.contains("rate")) return AiTaskErrorCode.RATE_LIMITED;
        if (normalized.contains("timeout") || normalized.contains("timed out")) return AiTaskErrorCode.TIMED_OUT;
        if (normalized.contains("model") && normalized.contains("not")) return AiTaskErrorCode.MODEL_NOT_FOUND;
        return AiTaskErrorCode.PROVIDER_UNAVAILABLE;
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "AI Provider 调用失败。";
        return message.replaceAll("(?i)(bearer\\s+)[^\\s]+", "$1[已隐藏]")
            .replaceAll("(?i)(api[_ -]?key|token|password)\\s*[:=]\\s*\\S+", "$1=[已隐藏]");
    }
}
