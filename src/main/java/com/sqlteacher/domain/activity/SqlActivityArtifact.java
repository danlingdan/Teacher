package com.sqlteacher.domain.activity;

import java.util.Objects;

public record SqlActivityArtifact(String submittedSql) implements ActivityArtifact {
    public SqlActivityArtifact {
        Objects.requireNonNull(submittedSql, "submittedSql must not be null");
        if (submittedSql.isBlank()) {
            throw new IllegalArgumentException("submittedSql must not be blank");
        }
    }

    @Override
    public ActivityType type() {
        return ActivityType.SQL;
    }
}
