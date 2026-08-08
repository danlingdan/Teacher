package com.sqlteacher.domain.course;

import java.util.Objects;

public record KnowledgePointRelation(
    String sourceKnowledgePointId,
    String targetKnowledgePointId,
    KnowledgeRelationType type
) {
    public KnowledgePointRelation {
        sourceKnowledgePointId = required(sourceKnowledgePointId, "sourceKnowledgePointId");
        targetKnowledgePointId = required(targetKnowledgePointId, "targetKnowledgePointId");
        type = Objects.requireNonNull(type, "type must not be null");
        if (sourceKnowledgePointId.equals(targetKnowledgePointId)) {
            throw new IllegalArgumentException("knowledge point relation cannot reference itself");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
