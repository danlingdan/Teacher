package com.sqlteacher.infrastructure.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

final class PromptTemplateLoader {
    private PromptTemplateLoader() {
    }

    static String render(String resourcePath, Map<String, String> values) {
        Objects.requireNonNull(resourcePath, "resourcePath must not be null");
        Objects.requireNonNull(values, "values must not be null");
        String template;
        try (InputStream input = PromptTemplateLoader.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Missing prompt template: " + resourcePath);
            }
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read prompt template: " + resourcePath, error);
        }
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", Objects.requireNonNull(entry.getValue()));
        }
        return rendered.strip();
    }
}
