package com.sqlteacher.application.component;

import java.util.Objects;

public record ManagedComponentStatus(
    ManagedComponentId id,
    String displayName,
    State state,
    String detail,
    String source,
    String license,
    boolean requiresAdministrator,
    boolean restartMayBeRequired
) {
    public ManagedComponentStatus {
        id = Objects.requireNonNull(id);
        displayName = require(displayName, "displayName");
        state = Objects.requireNonNull(state);
        detail = Objects.requireNonNullElse(detail, "").trim();
        source = require(source, "source");
        license = require(license, "license");
    }

    public boolean ready() {
        return state == State.READY;
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    public enum State {
        READY,
        MISSING,
        INSTALLING,
        RESTART_REQUIRED,
        FAILED
    }
}
