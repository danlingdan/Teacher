package com.sqlteacher.application.exercise;

import java.util.List;
import java.util.Objects;

/**
 * Display-only AI explanation request for a failed submission. Everything in the request
 * is already student-visible content; expected answers and result rows must never be
 * attached by the caller.
 */
public record ExerciseExplainRequest(
    String title,
    String description,
    String knowledgePoint,
    String exerciseType,
    String studentSql,
    List<String> feedback
) {
    private static final int MAX_STUDENT_SQL = 4_000;
    private static final int MAX_FEEDBACK = 2_000;

    public ExerciseExplainRequest {
        title = bounded(requireText(title, "title"), 200);
        description = bounded(description == null ? "" : description, 2_000);
        knowledgePoint = knowledgePoint == null ? "" : knowledgePoint.trim();
        exerciseType = exerciseType == null ? "QUERY" : exerciseType.trim();
        studentSql = bounded(requireText(studentSql, "studentSql"), MAX_STUDENT_SQL);
        feedback = List.copyOf(Objects.requireNonNull(feedback, "feedback must not be null"));
    }

    /** Bounded feedback text for the prompt budget. */
    public String feedbackText() {
        String joined = String.join("\n", feedback).strip();
        return bounded(joined, MAX_FEEDBACK);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String bounded(String value, int maxLength) {
        String normalized = value.strip();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }
}
