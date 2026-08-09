package com.sqlteacher.domain.activity;

import java.util.Objects;

public record SimulationAction(String id, String label, String fromStateId, String toStateId,
                               String explanation) {
    public SimulationAction {
        id = required(id, "id");
        label = required(label, "label");
        fromStateId = required(fromStateId, "fromStateId");
        toStateId = required(toStateId, "toStateId");
        explanation = required(explanation, "explanation");
        if (fromStateId.equals(toStateId)) {
            throw new IllegalArgumentException("simulation actions must change state");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
