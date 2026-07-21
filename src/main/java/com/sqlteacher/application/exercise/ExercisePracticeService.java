package com.sqlteacher.application.exercise;

public interface ExercisePracticeService {
    ExerciseSession start(String exerciseId);

    ExerciseAttemptResult run(String sessionId, String sql);

    ExerciseAttemptResult submit(String sessionId, String sql);

    ExerciseHint requestHint(String sessionId);
}
