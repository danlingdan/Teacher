package com.sqlteacher.domain.activity;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record QuizActivitySpecification(int formatVersion, List<QuizQuestion> questions, int passPercent)
        implements ActivitySpecification {
    public QuizActivitySpecification {
        if (formatVersion < 1) throw new IllegalArgumentException("formatVersion must be positive");
        questions = List.copyOf(Objects.requireNonNull(questions, "questions must not be null"));
        if (questions.isEmpty()) throw new IllegalArgumentException("questions must not be empty");
        if (new HashSet<>(questions.stream().map(QuizQuestion::id).toList()).size() != questions.size()) {
            throw new IllegalArgumentException("question ids must be unique");
        }
        if (passPercent < 1 || passPercent > 100) {
            throw new IllegalArgumentException("passPercent must be between 1 and 100");
        }
    }

    @Override public ActivityType type() { return ActivityType.QUIZ; }
}
