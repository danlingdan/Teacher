package com.sqlteacher.domain.activity;

public sealed interface ActivityArtifact permits CodeActivityArtifact, LabActivityArtifact, ProjectActivityArtifact,
        QuizActivityArtifact, ReadingActivityArtifact, SimulationActivityArtifact, SqlActivityArtifact,
        TraceActivityArtifact {
    ActivityType type();
}
