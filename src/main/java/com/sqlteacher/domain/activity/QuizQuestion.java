package com.sqlteacher.domain.activity;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record QuizQuestion(
    String id,
    String prompt,
    List<QuizOption> options,
    String correctOptionId,
    String explanation
) {
    public QuizQuestion {
        id = required(id, "id");
        prompt = required(prompt, "prompt");
        options = List.copyOf(Objects.requireNonNull(options, "options must not be null"));
        if (options.size() < 2) throw new IllegalArgumentException("a question needs at least two options");
        if (new HashSet<>(options.stream().map(QuizOption::id).toList()).size() != options.size()) {
            throw new IllegalArgumentException("option ids must be unique");
        }
        String normalizedCorrectOptionId = required(correctOptionId, "correctOptionId");
        if (options.stream().noneMatch(option -> option.id().equals(normalizedCorrectOptionId))) {
            throw new IllegalArgumentException("correctOptionId must reference an option");
        }
        correctOptionId = normalizedCorrectOptionId;
        explanation = required(explanation, "explanation");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
