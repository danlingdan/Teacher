package com.sqlteacher.application.ai;

import java.net.URI;
import java.util.Objects;

/** Non-sensitive, device-local AI provider metadata. Credentials are referenced, never embedded. */
public record AiProviderProfile(
    String id,
    String displayName,
    AiProviderKind kind,
    URI endpoint,
    String model,
    boolean enabled,
    String credentialReference
) {
    public AiProviderProfile {
        requireText(id, "id");
        requireText(displayName, "displayName");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        requireText(model, "model");
        if (kind == AiProviderKind.OPENAI_COMPATIBLE) {
            requireText(credentialReference, "credentialReference");
        }
        validateEndpoint(endpoint);
    }

    private static void validateEndpoint(URI endpoint) {
        String scheme = endpoint.getScheme();
        String host = endpoint.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI");
        }
        if (endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("endpoint must not contain credentials, query parameters, or fragments");
        }
        if ("https".equalsIgnoreCase(scheme)) return;
        if ("http".equalsIgnoreCase(scheme) && isLoopback(host)) return;
        throw new IllegalArgumentException("AI provider endpoint must use HTTPS; HTTP is loopback-only");
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
