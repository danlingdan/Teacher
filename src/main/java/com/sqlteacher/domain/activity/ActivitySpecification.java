package com.sqlteacher.domain.activity;

public sealed interface ActivitySpecification permits SqlActivitySpecification {
    ActivityType type();

    int formatVersion();
}
