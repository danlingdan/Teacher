package com.sqlteacher.application.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActivityEvidencePolicyTest {
    @Test
    void freezesDifferentWeightsForDifferentEvidenceKinds() {
        assertEquals(40, ActivityEvidencePolicy.weight("READING"));
        assertEquals(60, ActivityEvidencePolicy.weight("QUIZ"));
        assertEquals(100, ActivityEvidencePolicy.weight("SQL"));
        assertEquals(110, ActivityEvidencePolicy.weight("LAB"));
        assertEquals(120, ActivityEvidencePolicy.weight("PROJECT"));
        assertEquals(100, ActivityEvidencePolicy.weight("EXERCISE_PASSED"));
    }
}
