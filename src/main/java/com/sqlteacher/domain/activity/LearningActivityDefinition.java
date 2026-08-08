package com.sqlteacher.domain.activity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record LearningActivityDefinition(
    String id,
    String courseId,
    String sectionId,
    String title,
    String description,
    List<String> knowledgePointIds,
    ActivityDifficulty difficulty,
    int estimatedMinutes,
    int version,
    boolean enabled,
    ActivitySpecification specification,
    Instant createdAt,
    Instant updatedAt
) {
    public LearningActivityDefinition {
        id = requireText(id, "id");
        courseId = requireText(courseId, "courseId");
        sectionId = requireText(sectionId, "sectionId");
        title = requireText(title, "title");
        description = requireText(description, "description");
        knowledgePointIds = List.copyOf(Objects.requireNonNull(knowledgePointIds, "knowledgePointIds must not be null"));
        if (knowledgePointIds.isEmpty() || knowledgePointIds.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("knowledgePointIds must contain non-blank values");
        }
        difficulty = Objects.requireNonNull(difficulty, "difficulty must not be null");
        if (estimatedMinutes < 1 || version < 1) {
            throw new IllegalArgumentException("estimatedMinutes and version must be positive");
        }
        specification = Objects.requireNonNull(specification, "specification must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public ActivityType type() {
        return specification.type();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
