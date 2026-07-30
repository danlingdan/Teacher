package com.sqlteacher.application.ai;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Metadata-only preview of outbound AI context. It intentionally contains no prompt body. */
public record AiContextPreview(
    AiTaskType taskType,
    Set<AiContextCategory> categories,
    List<String> sources,
    int characterCount,
    List<String> redactions
) {
    public AiContextPreview {
        Objects.requireNonNull(taskType, "taskType must not be null");
        categories = Set.copyOf(Objects.requireNonNull(categories, "categories must not be null"));
        sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
        redactions = List.copyOf(Objects.requireNonNull(redactions, "redactions must not be null"));
        if (characterCount < 0) throw new IllegalArgumentException("characterCount must not be negative");
    }
}
