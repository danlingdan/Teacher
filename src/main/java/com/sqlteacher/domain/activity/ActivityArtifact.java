package com.sqlteacher.domain.activity;

public sealed interface ActivityArtifact permits CodeActivityArtifact, QuizActivityArtifact, SqlActivityArtifact,
        TraceActivityArtifact {
    ActivityType type();
}
