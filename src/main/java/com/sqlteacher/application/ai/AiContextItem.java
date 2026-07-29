package com.sqlteacher.application.ai;

public record AiContextItem(AiContextCategory category, String source, String content) {
    public AiContextItem {
        if (category == null) throw new IllegalArgumentException("category must not be null");
        source = source == null || source.isBlank() ? "application" : source.strip();
        content = content == null ? "" : content;
    }
}
