package com.sqlteacher.domain.activity;

import java.util.Map;
import java.util.Objects;

public record QuizActivityArtifact(Map<String, String> selectedOptionIds) implements ActivityArtifact {
    public QuizActivityArtifact {
        selectedOptionIds = Map.copyOf(Objects.requireNonNull(selectedOptionIds, "selectedOptionIds must not be null"));
        if (selectedOptionIds.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank())) {
            throw new IllegalArgumentException("quiz selections must use non-blank ids");
        }
    }

    @Override public ActivityType type() { return ActivityType.QUIZ; }
}
