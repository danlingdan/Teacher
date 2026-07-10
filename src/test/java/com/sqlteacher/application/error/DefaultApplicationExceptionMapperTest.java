package com.sqlteacher.application.error;

import com.sqlteacher.domain.SqlTeacherException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultApplicationExceptionMapperTest {
    private final DefaultApplicationExceptionMapper mapper = new DefaultApplicationExceptionMapper();

    @Test
    void shouldPreserveKnownApplicationErrorCodeAndSafeMessage() {
        ApplicationError error = mapper.map(
            new SqlTeacherException("SQLITE_INIT_FAILED", "The demo database could not be prepared.")
        );

        assertEquals("SQLITE_INIT_FAILED", error.code());
        assertEquals(ApplicationErrorType.APPLICATION, error.type());
        assertEquals("The demo database could not be prepared.", error.userMessage());
        assertFalse(error.retryable());
    }

    @Test
    void shouldHideUnexpectedExceptionDetails() {
        ApplicationError error = mapper.map(new IllegalStateException("internal diagnostic detail"));

        assertEquals("UNEXPECTED_ERROR", error.code());
        assertEquals(ApplicationErrorType.UNEXPECTED, error.type());
        assertFalse(error.userMessage().contains("diagnostic"));
        assertTrue(error.retryable());
    }

    @Test
    void shouldProvideSafeFallbackForIncompleteApplicationException() {
        ApplicationError error = mapper.map(new SqlTeacherException(null, null));

        assertEquals("APPLICATION_ERROR", error.code());
        assertEquals("The operation could not be completed.", error.userMessage());
    }
}
