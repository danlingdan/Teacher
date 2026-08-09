package com.sqlteacher.domain.activity;

public sealed interface ActivitySpecification permits CodeActivitySpecification, LabActivitySpecification,
        ProjectActivitySpecification, QuizActivitySpecification, ReadingActivitySpecification,
        SimulationActivitySpecification, SqlActivitySpecification, TraceActivitySpecification {
    ActivityType type();

    int formatVersion();
}
