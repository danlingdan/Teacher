package com.sqlteacher.domain.activity;

import java.util.Objects;

public record SimulationCheckpoint(String id, String stateId, String title, String successMessage,
                                   String failureReasonCode) {
    public SimulationCheckpoint {
        id = required(id, "id");
        stateId = required(stateId, "stateId");
        title = required(title, "title");
        successMessage = required(successMessage, "successMessage");
        failureReasonCode = required(failureReasonCode, "failureReasonCode");
        if (!failureReasonCode.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("failureReasonCode must be a stable upper-case reason code");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
