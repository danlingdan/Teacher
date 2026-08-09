package com.sqlteacher.domain.activity;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record LabActivitySpecification(int formatVersion, String prompt, List<LabStep> steps,
                                       int minimumConclusionCharacters) implements ActivitySpecification {
    public LabActivitySpecification {
        if (formatVersion < 1) throw new IllegalArgumentException("formatVersion must be positive");
        prompt = required(prompt, "prompt", 2_000);
        steps = List.copyOf(Objects.requireNonNull(steps, "steps must not be null"));
        if (steps.isEmpty() || steps.size() > 50) throw new IllegalArgumentException("steps size is invalid");
        if (new HashSet<>(steps.stream().map(LabStep::id).toList()).size() != steps.size()
                || new HashSet<>(steps.stream().map(LabStep::observationKey).toList()).size() != steps.size()) {
            throw new IllegalArgumentException("lab step and observation ids must be unique");
        }
        if (minimumConclusionCharacters < 20 || minimumConclusionCharacters > 2_000) {
            throw new IllegalArgumentException("minimumConclusionCharacters is invalid");
        }
    }

    @Override public ActivityType type() { return ActivityType.LAB; }

    private static String required(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) throw new IllegalArgumentException(name + " is invalid");
        return normalized;
    }
}
