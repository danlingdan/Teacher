package com.sqlteacher.application.exercise;

import java.util.List;

/**
 * v3.5.0 EPATH-1/2: chapter learning paths distributed with the exercise bank, joined
 * with the current learner's real progress (owner-scoped attempts and mastery). The
 * status values are read-only views over authoritative learning state; the path itself
 * never grants or records progress.
 */
public interface ExercisePathService {
    List<PathView> listPaths();

    record PathView(String id, String name, int version, List<ChapterView> chapters) {
        public PathView {
            chapters = chapters == null ? List.of() : List.copyOf(chapters);
        }
    }

    record ChapterView(int order, String title, List<String> knowledgeTags, List<PathExercise> exercises) {
        public ChapterView {
            knowledgeTags = knowledgeTags == null ? List.of() : List.copyOf(knowledgeTags);
            exercises = exercises == null ? List.of() : List.copyOf(exercises);
        }
    }

    record PathExercise(
        String exerciseId,
        String title,
        String knowledgePoint,
        int attempts,
        boolean passed,
        Integer masteryPercent
    ) {
    }
}
