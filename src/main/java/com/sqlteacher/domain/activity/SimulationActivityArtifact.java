package com.sqlteacher.domain.activity;

import java.util.List;
import java.util.Objects;

public record SimulationActivityArtifact(List<String> actionIds) implements ActivityArtifact {
    public SimulationActivityArtifact {
        actionIds = List.copyOf(Objects.requireNonNull(actionIds, "actionIds must not be null"));
        if (actionIds.size() > 256 || actionIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("actionIds must contain at most 256 non-blank values");
        }
    }

    @Override public ActivityType type() { return ActivityType.SIMULATION; }
}
