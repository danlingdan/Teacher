package com.sqlteacher.domain.activity;

public sealed interface ActivityArtifact permits CodeActivityArtifact, QuizActivityArtifact, SqlActivityArtifact,
        ProjectActivityArtifact, SimulationActivityArtifact, TraceActivityArtifact {
    ActivityType type();
}
