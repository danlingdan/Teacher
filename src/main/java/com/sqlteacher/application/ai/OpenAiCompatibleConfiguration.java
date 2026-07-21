package com.sqlteacher.application.ai;

import java.net.URI;
import java.util.Objects;
import java.util.Arrays;

/**
 * 用户自带密钥的 OpenAI-compatible 网络模型配置。
 * API Key 仅用于一次请求，调用方不得持久化、同步或记录该值。
 */
public record OpenAiCompatibleConfiguration(URI endpoint, String model, char[] apiKey) {
    public OpenAiCompatibleConfiguration {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("Network AI endpoint must use HTTPS");
        }
        if (model.isBlank() || apiKey.length == 0) {
            throw new IllegalArgumentException("model and apiKey must not be blank");
        }
        apiKey = apiKey.clone();
    }

    @Override
    public char[] apiKey() {
        return apiKey.clone();
    }

    public void destroy() { Arrays.fill(apiKey, '\0'); }
}
