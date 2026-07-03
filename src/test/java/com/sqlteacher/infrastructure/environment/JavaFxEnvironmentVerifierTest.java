package com.sqlteacher.infrastructure.environment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaFxEnvironmentVerifierTest {
    private final JavaFxEnvironmentVerifier verifier = new JavaFxEnvironmentVerifier();

    @Test
    void shouldFindJavaFxRuntimeClasses() {
        assertEquals(VerificationStatus.PASS, verifier.verifyRuntime().status());
    }

    @Test
    void shouldReportGraphicsEnvironmentWithoutThrowing() {
        VerificationStatus status = verifier.verifyGraphicsEnvironment().status();

        assertTrue(status == VerificationStatus.PASS || status == VerificationStatus.WARNING);
    }
}
