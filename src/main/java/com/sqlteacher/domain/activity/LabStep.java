package com.sqlteacher.domain.activity;

import java.util.Objects;

public record LabStep(String id, String title, String instruction, String observationKey) {
    public LabStep {
        id = required(id, "id", 64);
        title = required(title, "title", 160);
        instruction = required(instruction, "instruction", 1_000);
        observationKey = required(observationKey, "observationKey", 64);
    }

    private static String required(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }
}
