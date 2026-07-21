package com.sqlteacher.application.exercise;

public interface ExercisePracticeService {
    ExerciseSession start(String exerciseId);

    ExerciseSession reset(String sessionId);

    ExerciseAttemptResult run(String sessionId, String sql);

    ExerciseAttemptResult submit(String sessionId, String sql);

    ExerciseHint requestHint(String sessionId);

    void close(String sessionId);
}
