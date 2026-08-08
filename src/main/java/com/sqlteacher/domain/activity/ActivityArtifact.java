package com.sqlteacher.domain.activity;

public sealed interface ActivityArtifact permits SqlActivityArtifact {
    ActivityType type();
}
