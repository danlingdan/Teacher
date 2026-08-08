package com.sqlteacher.application.course;

import com.sqlteacher.domain.activity.ActivityDifficulty;
import com.sqlteacher.domain.activity.ActivityType;

import java.util.List;
import java.util.Objects;

public record CourseMapActivity(
    String id,
    String title,
    ActivityType type,
    ActivityDifficulty difficulty,
    int estimatedMinutes,
    boolean enabled,
    List<String> knowledgePoints
) {
    public CourseMapActivity {
        id = required(id, "id");
        title = required(title, "title");
        type = Objects.requireNonNull(type, "type must not be null");
        difficulty = Objects.requireNonNull(difficulty, "difficulty must not be null");
        if (estimatedMinutes < 1) throw new IllegalArgumentException("estimatedMinutes must be positive");
        knowledgePoints = List.copyOf(Objects.requireNonNull(knowledgePoints, "knowledgePoints must not be null"));
    }

    @Override
    public String toString() {
        return title;
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
