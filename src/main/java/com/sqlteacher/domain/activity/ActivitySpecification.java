package com.sqlteacher.domain.activity;

public sealed interface ActivitySpecification permits CodeActivitySpecification, QuizActivitySpecification,
        ProjectActivitySpecification, SimulationActivitySpecification, SqlActivitySpecification,
        TraceActivitySpecification {
    ActivityType type();

    int formatVersion();
}
