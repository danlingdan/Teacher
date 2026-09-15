package com.sqlteacher.domain.exercise;

import java.util.List;
import java.util.Objects;

/**
 * v3.5.0 EPATH-1: a chapter-organized learning path distributed with the exercise bank.
 * Chapters carry an explicit order, a textbook-style title, knowledge tags (used to link
 * related course knowledge articles), and the exercise ids in practice order. The path is
 * presentational scaffolding only — mastery and progression stay computed from real
 * learning events, never from this file.
 */
public record ExerciseChapterPath(
    String id,
    String name,
    int version,
    List<Chapter> chapters
) {
    public ExerciseChapterPath {
        id = Objects.requireNonNull(id, "id must not be null").trim();
        name = Objects.requireNonNull(name, "name must not be null").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        chapters = List.copyOf(Objects.requireNonNull(chapters, "chapters must not be null"));
    }

    public record Chapter(int order, String title, List<String> knowledgeTags, List<String> exerciseIds) {
        public Chapter {
            if (order < 1) {
                throw new IllegalArgumentException("chapter order starts at 1");
            }
            title = Objects.requireNonNull(title, "title must not be null").trim();
            if (title.isEmpty()) {
                throw new IllegalArgumentException("chapter title must not be blank");
            }
            knowledgeTags = List.copyOf(Objects.requireNonNull(knowledgeTags, "knowledgeTags must not be null"));
            exerciseIds = List.copyOf(Objects.requireNonNull(exerciseIds, "exerciseIds must not be null"));
        }
    }
}
