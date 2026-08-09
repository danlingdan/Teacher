package com.sqlteacher.domain.activity;

import java.util.Map;
import java.util.Objects;

public record ReadingActivityArtifact(boolean readToEnd, Map<String, String> answers) implements ActivityArtifact {
    public ReadingActivityArtifact {
        answers = Map.copyOf(Objects.requireNonNull(answers, "answers must not be null"));
        if (answers.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                || entry.getValue() == null || entry.getValue().length() > 2_000)) {
            throw new IllegalArgumentException("reading answers are invalid");
        }
    }

    @Override public ActivityType type() { return ActivityType.READING; }
}
