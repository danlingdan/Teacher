package com.sqlteacher.domain.activity;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record LabActivityArtifact(List<String> completedStepIds, Map<String, String> observations,
                                  String conclusion) implements ActivityArtifact {
    public LabActivityArtifact {
        completedStepIds = List.copyOf(Objects.requireNonNull(completedStepIds, "completedStepIds must not be null"));
        observations = Map.copyOf(Objects.requireNonNull(observations, "observations must not be null"));
        conclusion = Objects.requireNonNull(conclusion, "conclusion must not be null").trim();
        if (completedStepIds.stream().anyMatch(value -> value == null || value.isBlank())
                || observations.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().length() > 4_000)
                || conclusion.length() > 8_000) {
            throw new IllegalArgumentException("lab artifact is invalid");
        }
    }

    @Override public ActivityType type() { return ActivityType.LAB; }
}
