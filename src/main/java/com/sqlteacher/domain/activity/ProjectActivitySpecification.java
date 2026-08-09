package com.sqlteacher.domain.activity;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ProjectActivitySpecification(
    int formatVersion,
    String prompt,
    List<ProjectMilestone> milestones,
    List<ProjectRubricCriterion> rubric,
    int minimumEvidenceCharacters,
    int minimumReflectionCharacters
) implements ActivitySpecification {
    public ProjectActivitySpecification {
        if (formatVersion < 1) throw new IllegalArgumentException("formatVersion must be positive");
        prompt = required(prompt, "prompt");
        milestones = List.copyOf(Objects.requireNonNull(milestones, "milestones must not be null"));
        rubric = List.copyOf(Objects.requireNonNull(rubric, "rubric must not be null"));
        if (milestones.isEmpty() || milestones.size() > 20 || rubric.isEmpty() || rubric.size() > 12) {
            throw new IllegalArgumentException("project requires bounded milestones and rubric criteria");
        }
        if (new HashSet<>(milestones.stream().map(ProjectMilestone::id).toList()).size() != milestones.size()
                || new HashSet<>(rubric.stream().map(ProjectRubricCriterion::id).toList()).size() != rubric.size()) {
            throw new IllegalArgumentException("project ids must be unique");
        }
        if (rubric.stream().mapToInt(ProjectRubricCriterion::weight).sum() != 100) {
            throw new IllegalArgumentException("project rubric weights must total 100");
        }
        if (minimumEvidenceCharacters < 1 || minimumEvidenceCharacters > 2_000
                || minimumReflectionCharacters < 1 || minimumReflectionCharacters > 2_000) {
            throw new IllegalArgumentException("project text gates are invalid");
        }
    }

    @Override public ActivityType type() { return ActivityType.PROJECT; }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
