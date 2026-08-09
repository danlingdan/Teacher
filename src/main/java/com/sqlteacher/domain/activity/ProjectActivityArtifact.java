package com.sqlteacher.domain.activity;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Privacy-minimized project evidence. Source archives and repository credentials are never embedded. */
public record ProjectActivityArtifact(
    int submissionVersion,
    List<String> completedMilestoneIds,
    String evidenceSummary,
    String reflection
) implements ActivityArtifact {
    public ProjectActivityArtifact {
        if (submissionVersion < 1) throw new IllegalArgumentException("submissionVersion must be positive");
        completedMilestoneIds = List.copyOf(Objects.requireNonNull(completedMilestoneIds,
            "completedMilestoneIds must not be null"));
        if (completedMilestoneIds.size() > 20
                || completedMilestoneIds.stream().anyMatch(value -> value == null || value.isBlank())
                || new HashSet<>(completedMilestoneIds).size() != completedMilestoneIds.size()) {
            throw new IllegalArgumentException("completed milestones are invalid");
        }
        evidenceSummary = bounded(evidenceSummary, "evidenceSummary", 8_000);
        reflection = bounded(reflection, "reflection", 8_000);
    }

    @Override public ActivityType type() { return ActivityType.PROJECT; }

    private static String bounded(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.length() > maximum) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }
}
