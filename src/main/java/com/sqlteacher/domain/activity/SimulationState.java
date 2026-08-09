package com.sqlteacher.domain.activity;

import java.util.List;
import java.util.Objects;

public record SimulationState(String id, String title, String description, List<String> observations) {
    public SimulationState {
        id = required(id, "id");
        title = required(title, "title");
        description = required(description, "description");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations must not be null"));
        if (observations.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("observations must not contain blank values");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
