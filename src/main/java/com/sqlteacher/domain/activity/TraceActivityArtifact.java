package com.sqlteacher.domain.activity;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record TraceActivityArtifact(List<String> visitedNodeIds) implements ActivityArtifact {
    public TraceActivityArtifact {
        visitedNodeIds = List.copyOf(Objects.requireNonNull(visitedNodeIds, "visitedNodeIds must not be null"));
        if (visitedNodeIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("visitedNodeIds must not contain blank values");
        }
        if (new HashSet<>(visitedNodeIds).size() != visitedNodeIds.size()) {
            throw new IllegalArgumentException("visited nodes must not repeat");
        }
    }

    @Override public ActivityType type() { return ActivityType.TRACE; }
}
