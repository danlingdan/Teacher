package com.sqlteacher.domain.activity;

public sealed interface ActivitySpecification permits CodeActivitySpecification, QuizActivitySpecification,
        SqlActivitySpecification, TraceActivitySpecification {
    ActivityType type();

    int formatVersion();
}
