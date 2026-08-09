package com.sqlteacher.application.learning;

import com.sqlteacher.domain.activity.ActivityType;

/** Versioned deterministic evidence weights; lower-weight reading/quiz evidence cannot dominate richer work. */
public final class ActivityEvidencePolicy {
    public static final String VERSION = "activity-evidence-policy-beta1-r1";

    private ActivityEvidencePolicy() { }

    public static int weight(String evidenceKind) {
        if (evidenceKind == null) return 100;
        try {
            return switch (ActivityType.valueOf(evidenceKind)) {
                case READING -> 40;
                case QUIZ -> 60;
                case TRACE -> 80;
                case SQL, SIMULATION -> 100;
                case CODE, LAB -> 110;
                case PROJECT -> 120;
            };
        } catch (IllegalArgumentException ignored) {
            return 100;
        }
    }
}
