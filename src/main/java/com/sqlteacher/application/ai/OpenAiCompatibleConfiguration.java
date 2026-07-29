package com.sqlteacher.application.ai;

import java.net.URI;
import java.util.Objects;
import java.util.Arrays;

/**
 * 用户自带密钥的 OpenAI-compatible 网络模型配置。
 * API Key 只能存在于受控内存或当前 Windows 用户的 DPAPI 加密存储中，不得同步或记录。
 */
public record OpenAiCompatibleConfiguration(URI endpoint, String model, char[] apiKey) {
    public OpenAiCompatibleConfiguration {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw new IllegalArgumentException("Network AI endpoint must use HTTPS");
        }
        if (endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Network AI endpoint must not contain credentials, query parameters, or fragments");
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
