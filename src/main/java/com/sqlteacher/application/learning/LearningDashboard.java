package com.sqlteacher.application.learning;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record LearningDashboard(
    String ownerId,
    List<MasterySnapshot> mastery,
    List<LearningAction> actions,
    Instant generatedAt,
    Duration calculationTime,
    String policyVersion
) {
    public LearningDashboard {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId must not be blank");
        mastery = List.copyOf(Objects.requireNonNull(mastery, "mastery must not be null"));
        actions = List.copyOf(Objects.requireNonNull(actions, "actions must not be null"));
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        Objects.requireNonNull(calculationTime, "calculationTime must not be null");
        if (policyVersion == null || policyVersion.isBlank()) throw new IllegalArgumentException("policyVersion must not be blank");
    }
}
